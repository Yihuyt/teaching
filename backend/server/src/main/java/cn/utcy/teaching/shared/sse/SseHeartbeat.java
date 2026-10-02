package cn.utcy.teaching.shared.sse;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 长回合 SSE 的保活:模型思考期间没有正文可发,每次思考增量到来时 tick 一下,
 * 距上次心跳满 5 秒就发一个 heartbeat 事件,让连接不被中间设备判为空闲。
 */
public final class SseHeartbeat {

    static final long INTERVAL_MS = 5_000;

    private final Consumer<Map<String, Object>> emit;
    private long last;

    public SseHeartbeat(Consumer<Map<String, Object>> emit) {
        this.emit = emit;
    }

    public void tick() {
        long now = System.currentTimeMillis();
        if (now - last >= INTERVAL_MS) {
            last = now;
            emit.accept(Map.of("type", "heartbeat"));
        }
    }
}
