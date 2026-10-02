package cn.utcy.teaching.judge.worker;

import cn.utcy.teaching.judge.config.JudgeProperties;
import cn.utcy.teaching.judge.model.JudgeJob;
import cn.utcy.teaching.judge.model.JudgeResult;
import cn.utcy.teaching.judgecontract.TestcasePackage;
import cn.utcy.teaching.judgecontract.TestcasePackageException;
import cn.utcy.teaching.judge.packagefile.TestcasePackageStore;
import cn.utcy.teaching.judge.sandbox.EvaluationEngine;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class JudgeStreamWorker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JudgeStreamWorker.class);
    private static final DefaultRedisScript<Long> REJECT_INVALID_JOB = new DefaultRedisScript<>("""
            local acknowledged = redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            if acknowledged ~= 1 then
              return redis.error_reply('invalid judge job acknowledge failed')
            end
            local deleted = redis.call('XDEL', KEYS[1], ARGV[2])
            if deleted ~= 1 then
              return redis.error_reply('invalid judge job delete failed')
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final StreamOperations<String, Object, Object> streams;
    private final JudgeProperties properties;
    private final JudgeJobCodec codec;
    private final TestcasePackageStore packageStore;
    private final EvaluationEngine evaluationEngine;
    private final AtomicResultPublisher resultPublisher;
    private final JudgeConsumerHealthIndicator consumerHealth;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, Long> activeMessages = new ConcurrentHashMap<>();
    private final Map<String, ActiveEvaluation> activeEvaluations = new ConcurrentHashMap<>();
    private final Semaphore capacity;
    private long lastRecoveryNanos = System.nanoTime() - Long.MAX_VALUE / 2;
    private final ExecutorService evaluations;
    private final ExecutorService poller;
    private final ScheduledExecutorService heartbeat;

    public JudgeStreamWorker(
            StringRedisTemplate redisTemplate,
            JudgeProperties properties,
            JudgeJobCodec codec,
            TestcasePackageStore packageStore,
            EvaluationEngine evaluationEngine,
            AtomicResultPublisher resultPublisher,
            JudgeConsumerHealthIndicator consumerHealth
    ) {
        this.redisTemplate = redisTemplate;
        this.streams = redisTemplate.opsForStream();
        this.properties = properties;
        this.codec = codec;
        this.packageStore = packageStore;
        this.evaluationEngine = evaluationEngine;
        this.resultPublisher = resultPublisher;
        this.consumerHealth = consumerHealth;
        this.capacity = new Semaphore(properties.concurrency());
        this.evaluations = Executors.newFixedThreadPool(
                properties.concurrency(),
                Thread.ofPlatform().name("judge-evaluation-", 0).factory()
        );
        this.poller = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("judge-poller").factory()
        );
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("judge-heartbeat").factory()
        );
    }

    @Override
    public void run(ApplicationArguments args) {
        createConsumerGroup();
        running.set(true);
        consumerHealth.started();
        heartbeat.scheduleWithFixedDelay(
                this::renewLeases,
                properties.heartbeat().toMillis(),
                properties.heartbeat().toMillis(),
                TimeUnit.MILLISECONDS
        );
        heartbeat.scheduleWithFixedDelay(
                this::superviseEvaluations,
                10_000,
                10_000,
                TimeUnit.MILLISECONDS
        );
        poller.submit(() -> {
            try {
                poll();
            } finally {
                consumerHealth.pollerStoppedUnexpectedly();
            }
        });
    }

    private void poll() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                consumerHealth.waitingForCapacity();
                capacity.acquire();
                consumerHealth.reading();
                Work work = recoverPendingIfDue();
                if (work == null) {
                    work = readNew();
                }
                consumerHealth.redisCycleSucceeded();
                if (work == null) {
                    capacity.release();
                    continue;
                }
                submit(work);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException exception) {
                capacity.release();
                consumerHealth.redisCycleFailed(exception);
                log.error("读取 Redis 判题任务失败", exception);
                waitAfterReadFailure();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Work readNew() {
        List<MapRecord<String, Object, Object>> records = streams.read(
                Consumer.from(properties.consumerGroup(), properties.consumerName()),
                StreamReadOptions.empty().count(1).block(properties.jobsReadBlock()),
                StreamOffset.create(properties.jobsStream(), ReadOffset.lastConsumed())
        );
        if (records == null || records.isEmpty()) {
            return null;
        }
        return new Work(records.getFirst(), false, 1);
    }

    /** 回收扫描(XPENDING 全组)按心跳间隔节流;租约 2 分钟,最迟晚 20 秒接手不影响时效 */
    private Work recoverPendingIfDue() {
        long now = System.nanoTime();
        if (now - lastRecoveryNanos < properties.heartbeat().toNanos()) {
            return null;
        }
        lastRecoveryNanos = now;
        return recoverPending();
    }

    private Work recoverPending() {
        PendingMessages pending = streams.pending(
                properties.jobsStream(),
                properties.consumerGroup(),
                Range.unbounded(),
                20
        );
        if (pending == null || pending.isEmpty()) {
            return null;
        }
        for (PendingMessage message : pending) {
            String id = message.getIdAsString();
            if (activeMessages.containsKey(id)
                    || message.getElapsedTimeSinceLastDelivery().compareTo(properties.lease()) < 0) {
                continue;
            }
            // 无论是继续评测还是判崩溃上限,都先 XCLAIM 认领:多副本下只有认领成功的那台处理,
            // 否则两台副本可能同时判同一条消息触顶、各发布一次 WORKER_CRASH_LIMIT 并各占一个评测槽位
            long deliveries = message.getTotalDeliveryCount();
            List<MapRecord<String, Object, Object>> claimed = streams.claim(
                    properties.jobsStream(),
                    properties.consumerGroup(),
                    properties.consumerName(),
                    properties.lease(),
                    message.getId()
            );
            if (claimed == null || claimed.isEmpty()) {
                continue;
            }
            if (deliveries >= properties.maxDeliveries()) {
                return new Work(claimed.getFirst(), true, deliveries);
            }
            return new Work(claimed.getFirst(), false, deliveries + 1);
        }
        return null;
    }

    private void submit(Work work) {
        String messageId = work.record().getId().getValue();
        ActiveEvaluation evaluation = new ActiveEvaluation(work, java.time.Instant.now());
        activeEvaluations.put(messageId, evaluation);
        try {
            evaluation.future = evaluations.submit(() -> {
                consumerHealth.evaluationStarted(messageId);
                try {
                    process(work);
                } finally {
                    consumerHealth.evaluationFinished(messageId);
                    activeEvaluations.remove(messageId);
                    capacity.release();
                }
            });
        } catch (RejectedExecutionException exception) {
            activeEvaluations.remove(messageId);
            capacity.release();
            throw exception;
        }
    }

    /**
     * 单任务墙钟上限:超限即中断线程并直接发布系统错误(结果发布按 jobId:attempt 幂等,
     * 卡死线程若日后醒来其结果会被去重丢弃)。线程中断不了时 capacity 不归还,
     * 健康指示器按评测时长判 DOWN,由看门狗自杀交容器重启兜底。
     */
    private void superviseEvaluations() {
        if (!running.get()) {
            return;
        }
        java.time.Instant deadline = java.time.Instant.now().minus(properties.maxEvaluationWallClock());
        for (Map.Entry<String, ActiveEvaluation> entry : activeEvaluations.entrySet()) {
            ActiveEvaluation evaluation = entry.getValue();
            if (evaluation.timedOut || evaluation.startedAt.isAfter(deadline)) {
                continue;
            }
            evaluation.timedOut = true;
            log.error("判题任务超出墙钟上限 {}，中断并按系统错误结算，messageId={}",
                    properties.maxEvaluationWallClock(), entry.getKey());
            try {
                JudgeJob job = codec.decode(evaluation.work.record());
                resultPublisher.publish(
                        evaluation.work.record().getId(),
                        JudgeResult.systemError(job, job.attempt(), "评测超出墙钟上限"));
            } catch (RuntimeException exception) {
                log.error("墙钟超限任务无法发布系统错误结果，messageId={}", entry.getKey(), exception);
            }
            java.util.concurrent.Future<?> future = evaluation.future;
            if (future != null) {
                future.cancel(true);
            }
        }
    }

    private static final class ActiveEvaluation {
        private final Work work;
        private final java.time.Instant startedAt;
        private volatile java.util.concurrent.Future<?> future;
        private volatile boolean timedOut;

        private ActiveEvaluation(Work work, java.time.Instant startedAt) {
            this.work = work;
            this.startedAt = startedAt;
        }
    }

    private void process(Work work) {
        RecordId messageId = work.record().getId();
        String messageIdValue = messageId.getValue();
        activeMessages.put(messageIdValue, work.deliveryCount());
        try {
            JudgeJob job = codec.decode(work.record());
            if (work.crashLimit()) {
                resultPublisher.publish(messageId, JudgeResult.workerCrashLimit(job, job.attempt()));
                return;
            }
            try {
                TestcasePackage testcasePackage = packageStore.load(
                        job.problemId(),
                        job.testcaseSha256()
                );
                JudgeResult result = evaluationEngine.evaluate(job, testcasePackage, job.attempt());
                resultPublisher.publish(messageId, result);
            } catch (TestcasePackageException exception) {
                resultPublisher.publish(
                        messageId,
                        JudgeResult.systemError(job, job.attempt(), exception.getMessage())
                );
            }
        } catch (InvalidJudgeJobException exception) {
            handleInvalidJob(messageId, exception);
        } catch (RuntimeException exception) {
            log.error(
                    "判题任务执行中断，将等待租约到期后重领，messageId={}",
                    messageIdValue,
                    exception
            );
        } finally {
            activeMessages.remove(messageIdValue);
        }
    }

    private void handleInvalidJob(
            RecordId messageId,
            InvalidJudgeJobException exception
    ) {
        exception.identity().ifPresentOrElse(
                identity -> resultPublisher.publish(
                        messageId,
                        JudgeResult.invalidJob(
                                identity.jobId(),
                                identity.submissionId(),
                                identity.attempt(),
                                exception.getMessage()
                        )
                ),
                () -> {
                    Long rejected = redisTemplate.execute(
                            REJECT_INVALID_JOB,
                            List.of(properties.jobsStream()),
                            properties.consumerGroup(),
                            messageId.getValue()
                    );
                    if (!Long.valueOf(1).equals(rejected)) {
                        throw new IllegalStateException("无法原子拒绝非法判题任务", exception);
                    }
                    log.error(
                            "已拒绝无法识别任务标识的非法判题任务，messageId={}，原因={}",
                            messageId.getValue(),
                            exception.getMessage()
                    );
                }
        );
    }

    private void renewLeases() {
        if (!running.get()) {
            return;
        }
        for (Map.Entry<String, Long> active : activeMessages.entrySet()) {
            try {
                redisTemplate.execute((RedisConnection connection) -> connection.execute(
                        "XCLAIM",
                        bytes(properties.jobsStream()),
                        bytes(properties.consumerGroup()),
                        bytes(properties.consumerName()),
                        bytes("0"),
                        bytes(active.getKey()),
                        bytes("RETRYCOUNT"),
                        bytes(Long.toString(active.getValue())),
                        bytes("JUSTID")
                ));
            } catch (DataAccessException exception) {
                log.error("续订判题任务租约失败，messageId={}", active.getKey(), exception);
            }
        }
    }

    private void createConsumerGroup() {
        try {
            redisTemplate.execute((RedisConnection connection) -> connection.execute(
                    "XGROUP",
                    bytes("CREATE"),
                    bytes(properties.jobsStream()),
                    bytes(properties.consumerGroup()),
                    bytes("0"),
                    bytes("MKSTREAM")
            ));
        } catch (RedisSystemException exception) {
            if (!containsBusyGroup(exception)) {
                throw exception;
            }
        }
    }

    private boolean containsBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void waitAfterReadFailure() {
        try {
            Thread.sleep(properties.readFailureDelay());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        consumerHealth.stopping();
        poller.shutdownNow();
        heartbeat.shutdownNow();
        evaluations.shutdownNow();
        try {
            evaluations.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 优雅停机:没有在途任务就把自己从消费者组里删掉(容器每次重启换主机名,否则死消费者越积越多);
     * 还有在途任务时保留,让别的副本按租约超时接手。失败只记日志,后端也会定期清理长期离线的消费者。
     * 挂在 ContextClosedEvent 上而不是 @PreDestroy:事件在生命周期组件停止之前发布,此时 Redis 连接还活着。
     */
    @EventListener(ContextClosedEvent.class)
    public void deregisterConsumer() {
        running.set(false);
        poller.shutdownNow();
        if (!activeEvaluations.isEmpty()) {
            return;
        }
        try {
            // 走类型化 API:XGROUP DELCONSUMER 返回整数,通用 execute 的字节数组输出不接受整数回复
            streams.deleteConsumer(
                    properties.jobsStream(),
                    Consumer.from(properties.consumerGroup(), properties.consumerName()));
        } catch (RuntimeException exception) {
            log.warn("从消费者组注销失败：{}", properties.consumerName(), exception);
        }
    }

    private record Work(
            MapRecord<String, Object, Object> record,
            boolean crashLimit,
            long deliveryCount
    ) {
    }
}
