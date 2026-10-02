package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.JudgeJobStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 判题作业发布轮询:只捞取待发布作业,逐条交给独立事务的执行器。
 * 单条失败记录日志后继续,不允许一条坏记录阻塞同批其它提交(队头阻塞曾是全站卡评测成因之一)。
 */
@Component
class JudgeJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(JudgeJobPublisher.class);

    private final JudgeJobMapper jobs;
    private final JudgeJobPublicationExecutor executor;

    JudgeJobPublisher(JudgeJobMapper jobs, JudgeJobPublicationExecutor executor) {
        this.jobs = jobs;
        this.executor = executor;
    }

    @Scheduled(fixedDelay = 500)
    public void publishPendingJobs() {
        List<JudgeJob> pending = jobs.selectList(new LambdaQueryWrapper<JudgeJob>()
                .eq(JudgeJob::getStatus, JudgeJobStatus.PENDING)
                .orderByAsc(JudgeJob::getCreatedAt)
                .last("LIMIT 20"));
        for (JudgeJob job : pending) {
            try {
                executor.publishOne(job.getId());
            } catch (RuntimeException exception) {
                log.error("判题作业发布失败，跳过并继续下一条：{}", job.getId(), exception);
            }
        }
    }
}
