package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.util.BoundedParallel;
import cn.utcy.teaching.shared.sse.ProgressBus;
import cn.utcy.teaching.shared.run.StaleRunCleaner;
import cn.utcy.teaching.knowledgegraph.domain.BuildStatus;
import cn.utcy.teaching.knowledgegraph.domain.SectionStatus;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgegraphProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 教材构建的判滞巡检({@link StaleRunCleaner} 语义):判败时断点数据保留——阶段产物、小节、MinerU 台账都在,
 * 重试只跑未完成部分;取消请求悬而未决的按已取消收尾;在途小节回到待抽取,界面不留假的「抽取中」。
 */
@Component
class StaleGraphBuildCleaner extends StaleRunCleaner {

    static final String INTERRUPTED = "构建中断（服务重启或长时间无进展），可重试，已完成的部分保留";

    private final KnowledgeGraphBuildMapper builds;
    private final BuildSectionMapper sections;
    private final ProgressBus bus;
    private final TransactionOperations transactions;

    StaleGraphBuildCleaner(KnowledgeGraphBuildMapper builds, BuildSectionMapper sections,
                           KnowledgegraphProperties properties, ProgressBus bus,
                           TransactionOperations transactions, Clock clock) {
        super("教材构建", properties.progressTimeout(), clock);
        this.builds = builds;
        this.sections = sections;
        this.bus = bus;
        this.transactions = transactions;
    }

    @Override
    protected List<Long> candidates(LocalDateTime staleBefore) {
        LambdaQueryWrapper<KnowledgeGraphBuildEntity> query = new LambdaQueryWrapper<KnowledgeGraphBuildEntity>()
                .select(KnowledgeGraphBuildEntity::getId)
                .in(KnowledgeGraphBuildEntity::getStatus, BuildStatus.PARSING, BuildStatus.EXTRACTING);
        if (staleBefore != null) {
            query.and(inner -> inner.isNull(KnowledgeGraphBuildEntity::getProgressHeartbeatAt)
                    .or().lt(KnowledgeGraphBuildEntity::getProgressHeartbeatAt, staleBefore));
        }
        return builds.selectList(query).stream().map(KnowledgeGraphBuildEntity::getId).toList();
    }

    @Override
    protected String judge(long buildId, LocalDateTime staleBefore) {
        String message = transactions.execute(status -> judgeLocked(buildId, staleBefore));
        if (message != null) {
            bus.publish(KnowledgeGraphBuildPipeline.buildChannel(buildId),
                    Map.of("type", "error", "message", message));
            bus.terminate(KnowledgeGraphBuildPipeline.buildChannel(buildId));
        }
        return message;
    }

    private String judgeLocked(long buildId, LocalDateTime staleBefore) {
        KnowledgeGraphBuildEntity locked = builds.selectForUpdate(buildId);
        if (locked == null || !locked.isActive() || fresh(locked.getProgressHeartbeatAt(), staleBefore)) {
            return null;
        }
        String message = locked.isCancelRequested() ? BoundedParallel.CANCELLED : INTERRUPTED;
        locked.failed(message, now());
        builds.updateById(locked);
        for (BuildSectionEntity row : sections.selectList(new LambdaQueryWrapper<BuildSectionEntity>()
                .eq(BuildSectionEntity::getBuildId, buildId)
                .eq(BuildSectionEntity::getStatus, SectionStatus.RUNNING))) {
            row.resetToPending();
            sections.updateById(row);
        }
        return message;
    }
}
