package cn.utcy.teaching.knowledgebase.application;

import cn.utcy.teaching.knowledgebase.domain.DocumentState;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgebaseProperties;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaleDocumentIngestCleanerTest {

    private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final KbDocumentMapper documents = mock(KbDocumentMapper.class);
    private final KnowledgebaseProperties properties = mock(KnowledgebaseProperties.class);

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KbDocumentEntity.class);
    }

    private StaleDocumentIngestCleaner cleaner() {
        when(properties.progressTimeout()).thenReturn(Duration.ofMinutes(10));
        return new StaleDocumentIngestCleaner(documents, properties,
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private KbDocumentEntity running(LocalDateTime heartbeatAt) {
        KbDocumentEntity document = new KbDocumentEntity(1L, 3L, "讲义.pdf", NOW_LOCAL.minusHours(1));
        setId(document, 42L);
        document.start("run-token", heartbeatAt);
        when(documents.selectList(any(Wrapper.class))).thenReturn(List.of(document));
        when(documents.selectForUpdate(any(Long.class))).thenReturn(document);
        when(documents.updateById(any(KbDocumentEntity.class))).thenReturn(1);
        return document;
    }

    @Test
    @DisplayName("启动清剿:心跳再新鲜也判中断,run 标记清空")
    void bootSweepJudgesAllActive() {
        KbDocumentEntity document = running(NOW_LOCAL);
        cleaner().run(null);
        assertThat(document.getState()).isEqualTo(DocumentState.ERROR);
        assertThat(document.getErrorMessage()).isEqualTo(StaleDocumentIngestCleaner.INTERRUPTED);
        assertThat(document.getRunToken()).isNull();
    }

    @Test
    @DisplayName("定时巡检:心跳新鲜的不动")
    void periodicSweepSparesLive() {
        KbDocumentEntity document = running(NOW_LOCAL);
        cleaner().sweepStale();
        assertThat(document.getState()).isEqualTo(DocumentState.PARSING);
    }

    @Test
    @DisplayName("定时巡检:心跳停更超过 progressTimeout 的判中断")
    void periodicSweepJudgesStale() {
        KbDocumentEntity document = running(NOW_LOCAL.minusMinutes(11));
        cleaner().sweepStale();
        assertThat(document.getState()).isEqualTo(DocumentState.ERROR);
    }

    private static void setId(Object entity, long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
