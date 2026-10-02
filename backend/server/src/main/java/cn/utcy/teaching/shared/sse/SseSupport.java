package cn.utcy.teaching.shared.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 辅助:事件格式固定为 `data: {json}\n\n`(type 字段驱动),前端按此解析。任务跑在专用执行器上,客户端断开置中断标志。
 */
public final class SseSupport {

    private SseSupport() {
    }

    public interface EventSink {
        void emit(Object event);

        boolean cancelled();
    }

    /** 默认墙钟:卡住的上游调用不能永久占住执行器槽位(满员即全线 503) */
    public static final java.time.Duration DEFAULT_WALL_CLOCK = java.time.Duration.ofMinutes(30);

    public static SseEmitter run(TaskExecutor executor, ObjectMapper objectMapper,
                                 SseTask task) {
        return run(executor, objectMapper, DEFAULT_WALL_CLOCK, task);
    }

    public static SseEmitter run(TaskExecutor executor, ObjectMapper objectMapper,
                                 java.time.Duration wallClock, SseTask task) {
        SseEmitter emitter = new SseEmitter(wallClock.toMillis());
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));

        // 显式锁而非 synchronized:并行任务会从多个(虚拟)线程发事件,SseEmitter.send 非线程安全,
        // 且锁内是阻塞网络写,不能钉住载体线程
        java.util.concurrent.locks.ReentrantLock sendLock = new java.util.concurrent.locks.ReentrantLock();
        EventSink sink = new EventSink() {
            @Override
            public void emit(Object event) {
                if (cancelled.get()) {
                    return;
                }
                sendLock.lock();
                try {
                    // 事件格式固定:自定义序列化,不用 SseEmitter.event() 的默认包装
                    String json = objectMapper.writeValueAsString(event);
                    emitter.send(SseEmitter.event().data(json));
                } catch (IOException | IllegalStateException e) {
                    cancelled.set(true);
                } finally {
                    sendLock.unlock();
                }
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };

        executor.execute(() -> {
            try {
                task.run(sink);
            } catch (Exception e) {
                sink.emit(Map.of("type", "error",
                        "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                try {
                    emitter.complete();
                } catch (IllegalStateException ignored) {
                    // 客户端已断开
                }
            }
        });
        return emitter;
    }

    @FunctionalInterface
    public interface SseTask {
        void run(EventSink sink) throws Exception;
    }
}
