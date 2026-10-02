package cn.utcy.teaching.shared.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 直启长任务的写围栏与进展心跳,知识库入库、教材构建、视频生成共用一套语义:
 * 启动时发放 run 标记写在行上,之后每次落库(含心跳)都要求行上的标记仍是本次的——判滞巡检判败或
 * 新一次启动会换掉标记,迟到的旧线程写什么都不生效、结果作废。心跳随进展节流落库,证明"还活着且仍持有这行";
 * 停更超过模块的 progressTimeout 由 {@link StaleRunCleaner} 判失败。进程死亡不在此处理。
 */
public final class RunFence {

    /** 进展心跳的节流间隔:进展事件很密时至多这么久落一次库 */
    public static final Duration BEAT_INTERVAL = Duration.ofSeconds(5);

    private RunFence() {
    }

    public static String newToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * 一次运行的心跳。write 落一次库并回答围栏是否仍在手里(典型实现:UPDATE … WHERE id AND run_token,
     * 影响 0 行即失守);失守后一直失守,调用方在下一个安全边界停下、结果作废。
     */
    public static final class Heartbeat {

        private static final Logger log = LoggerFactory.getLogger(Heartbeat.class);

        private final Clock clock;
        private final BooleanSupplier write;
        private final AtomicBoolean lost = new AtomicBoolean();
        private volatile long lastBeatMillis;

        public Heartbeat(Clock clock, BooleanSupplier write) {
            this.clock = clock;
            this.write = write;
        }

        public boolean beat() {
            if (lost.get()) {
                return false;
            }
            long nowMillis = clock.millis();
            if (nowMillis - lastBeatMillis < BEAT_INTERVAL.toMillis()) {
                return true;
            }
            lastBeatMillis = nowMillis;
            try {
                if (!write.getAsBoolean()) {
                    lost.set(true);
                }
            } catch (RuntimeException exception) {
                log.warn("进展心跳落库失败:{}", exception.getMessage());
            }
            return !lost.get();
        }

        /** 其他落库发现围栏失守(锁行后标记已不是本次的)也在此登记 */
        public void markLost() {
            lost.set(true);
        }

        public boolean lost() {
            return lost.get();
        }
    }
}
