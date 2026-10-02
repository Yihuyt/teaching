package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.util.BoundedParallel;
import cn.utcy.teaching.shared.sse.ProgressBus;
import cn.utcy.teaching.knowledgegraph.domain.BuildStatus;
import cn.utcy.teaching.knowledgegraph.domain.SectionStatus;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 判滞巡检:启动时全部运行态行判中断(单实例,必是上一进程遗孤,不看心跳年龄);
 * 定时只判心跳停更超时的;取消悬置的按已取消收尾;在途小节归位待抽取。
 */
class StaleGraphBuildCleanerTest {

    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final KnowledgeGraphBuildMapper builds = mock(KnowledgeGraphBuildMapper.class);
    private final BuildSectionMapper sections = mock(BuildSectionMapper.class);
    private final StaleGraphBuildCleaner cleaner = new StaleGraphBuildCleaner(builds, sections,
            KnowledgeGraphBuildServiceTest.PROPERTIES, new ProgressBus(new ObjectMapper()),
            TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraphBuildEntity.class);
        TableInfoHelper.initTableInfo(assistant, BuildSectionEntity.class);
    }

    @SuppressWarnings("unchecked")
    private KnowledgeGraphBuildEntity active(BuildStatus status, LocalDateTime heartbeatAt) {
        KnowledgeGraphBuildEntity build = KnowledgeGraphBuildServiceTest.entity(1L, status);
        if (status == BuildStatus.PARSING) {
            build.reparse("fresh-token", heartbeatAt);
        } else {
            build.extracting("fresh-token", heartbeatAt);
        }
        when(builds.selectList(any(Wrapper.class))).thenReturn(List.of(build));
        when(builds.selectForUpdate(1L)).thenReturn(build);
        when(builds.updateById(any(KnowledgeGraphBuildEntity.class))).thenReturn(1);
        return build;
    }

    @Test
    @DisplayName("启动清剿:不看心跳年龄,运行态行一律判中断;在途小节回到待抽取")
    @SuppressWarnings("unchecked")
    void bootSweepJudgesAllActive() {
        KnowledgeGraphBuildEntity build = active(BuildStatus.EXTRACTING, NOW_LOCAL); // 心跳还很新鲜
        BuildSectionEntity running = new BuildSectionEntity(1L, 0, 0, "", "节", "章", 1, 1);
        running.running();
        when(sections.selectList(any(Wrapper.class))).thenReturn(List.of(running));
        when(sections.updateById(any(BuildSectionEntity.class))).thenReturn(1);

        cleaner.run(null);

        assertThat(build.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(build.getErrorMessage()).isEqualTo(StaleGraphBuildCleaner.INTERRUPTED);
        assertThat(build.getRunToken()).isNull();
        assertThat(running.getStatus()).isEqualTo(SectionStatus.PENDING);
    }

    @Test
    @DisplayName("定时巡检:心跳新鲜的执行者活着,不动")
    void periodicSweepSparesLiveBuilds() {
        KnowledgeGraphBuildEntity build = active(BuildStatus.PARSING, NOW_LOCAL);

        cleaner.sweepStale();

        assertThat(build.getStatus()).isEqualTo(BuildStatus.PARSING);
        assertThat(build.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("定时巡检:心跳停更超过 progressTimeout 的判中断")
    void periodicSweepJudgesStale() {
        KnowledgeGraphBuildEntity build = active(BuildStatus.PARSING, NOW_LOCAL.minusMinutes(21));
        when(sections.selectList(any(Wrapper.class))).thenReturn(List.of());

        cleaner.sweepStale();

        assertThat(build.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(build.getErrorMessage()).isEqualTo(StaleGraphBuildCleaner.INTERRUPTED);
    }

    @Test
    @DisplayName("取消悬置(执行者死前没来得及消费):按已取消收尾")
    void pendingCancelSettlesAsCancelled() {
        KnowledgeGraphBuildEntity build = active(BuildStatus.PARSING, NOW_LOCAL);
        build.requestCancel(NOW_LOCAL);
        when(sections.selectList(any(Wrapper.class))).thenReturn(List.of());

        cleaner.run(null);

        assertThat(build.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(build.getErrorMessage()).isEqualTo(BoundedParallel.CANCELLED);
    }
}
