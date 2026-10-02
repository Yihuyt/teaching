package cn.utcy.teaching.judge.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

@Component
public class JudgeRedisTimeoutContract implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(JudgeRedisTimeoutContract.class);
    static final Duration MIN_COMMAND_TIMEOUT_MARGIN = Duration.ofSeconds(5);

    private final Duration commandTimeout;
    private final Duration jobsReadBlock;
    private final Duration consumerStallTimeout;

    public JudgeRedisTimeoutContract(
            RedisProperties redisProperties,
            JudgeProperties judgeProperties
    ) {
        this.commandTimeout = redisProperties.getTimeout();
        this.jobsReadBlock = judgeProperties.jobsReadBlock();
        this.consumerStallTimeout = judgeProperties.consumerStallTimeout();
    }

    @Override
    public void afterPropertiesSet() {
        verify(commandTimeout, jobsReadBlock, consumerStallTimeout);
        log.info(
                "判题队列时序契约已确认：阻塞读取={}，Redis 命令超时={}，消费线程停滞阈值={}",
                jobsReadBlock,
                commandTimeout,
                consumerStallTimeout
        );
    }

    static void verify(
            Duration commandTimeout,
            Duration jobsReadBlock,
            Duration consumerStallTimeout
    ) {
        if (commandTimeout == null || commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalStateException("spring.data.redis.timeout 必须明确配置为大于 0 的时长");
        }
        Duration minimum = jobsReadBlock.plus(MIN_COMMAND_TIMEOUT_MARGIN);
        if (commandTimeout.compareTo(minimum) < 0) {
            throw new IllegalStateException(
                    "spring.data.redis.timeout 必须至少比 teaching.judge.jobs-read-block 多 5 秒："
                            + "当前命令超时=" + commandTimeout
                            + "，阻塞读取=" + jobsReadBlock
            );
        }
        if (consumerStallTimeout.compareTo(commandTimeout) <= 0) {
            throw new IllegalStateException(
                    "teaching.judge.consumer-stall-timeout 必须大于 spring.data.redis.timeout："
                            + "当前停滞阈值=" + consumerStallTimeout
                            + "，命令超时=" + commandTimeout
            );
        }
    }
}
