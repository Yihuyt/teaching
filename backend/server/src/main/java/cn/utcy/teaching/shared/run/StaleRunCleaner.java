package cn.utcy.teaching.shared.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 判滞巡检骨架:直启管线与进程同生共死,进程消失(发版 / 崩溃)或线程挂死时没有代码会写终态,
 * 行会带着停更的心跳卡在运行态、教师连重试按钮都等不到——这里把它们如实判失败。
 * 启动即跑且不看心跳年龄——单实例部署,启动时所有运行态的行必然是上一进程的遗孤;
 * 之后每 10 分钟只判心跳停更超过 progressTimeout 的(捕捉运行中挂死)。
 * 各模块只提供候选查询与"锁行复核后怎么判败"——断点数据怎么留、在途子项怎么归位是各自的业务。
 */
public abstract class StaleRunCleaner implements ApplicationRunner {

    private static final long SWEEP_INTERVAL_MILLIS = 600_000;

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final String taskLabel;
    private final Duration progressTimeout;
    private final Clock clock;

    protected StaleRunCleaner(String taskLabel, Duration progressTimeout, Clock clock) {
        this.taskLabel = taskLabel;
        this.progressTimeout = progressTimeout;
        this.clock = clock;
    }

    @Override
    public final void run(ApplicationArguments arguments) {
        sweep(null);
    }

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MILLIS, initialDelay = SWEEP_INTERVAL_MILLIS)
    public final void sweepStale() {
        sweep(now().minus(progressTimeout));
    }

    /** staleBefore 为 null = 启动清剿(全部运行态行);否则只判心跳停更超时的 */
    private void sweep(LocalDateTime staleBefore) {
        int judged = 0;
        for (long id : candidates(staleBefore)) {
            if (judge(id, staleBefore) != null) {
                judged++;
            }
        }
        if (judged > 0) {
            log.warn("判滞巡检:{} 个中断的{}已判失败", judged, taskLabel);
        }
    }

    /** 运行态行的 id;staleBefore 非空时只取心跳为空或早于它的 */
    protected abstract List<Long> candidates(LocalDateTime staleBefore);

    /** 锁行复核后判败;返回写入的失败文案,不该判(行已收尾、复核时心跳又跳了)返回 null */
    protected abstract String judge(long id, LocalDateTime staleBefore);

    /** 复核用:定时巡检下心跳仍新鲜 = 执行者活着,不动 */
    protected static boolean fresh(LocalDateTime heartbeatAt, LocalDateTime staleBefore) {
        return staleBefore != null && heartbeatAt != null && !heartbeatAt.isBefore(staleBefore);
    }

    protected final LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
