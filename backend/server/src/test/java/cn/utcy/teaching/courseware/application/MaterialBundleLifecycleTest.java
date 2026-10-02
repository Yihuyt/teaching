package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.ai.document.DocumentParser;
import cn.utcy.teaching.ai.document.MineruClient;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import cn.utcy.teaching.courseware.infrastructure.MaterialBundleEntity;
import cn.utcy.teaching.courseware.infrastructure.MaterialBundleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 素材包状态生命周期:行先建(parsing)、解析完置 ready;非 ready 的行读不出来
 * (崩溃遗留的半成品不会被当成空素材喂给大纲生成);discard 从行导出图片键入队。
 */
class MaterialBundleLifecycleTest {

    private final MaterialBundleMapper bundles = mock(MaterialBundleMapper.class);
    private final CoursewareAssetStorage assets = mock(CoursewareAssetStorage.class);
    private final CoursewareMaterialBundleService service = new CoursewareMaterialBundleService(
            mock(CoursewareApplicationService.class), mock(MineruClient.class), mock(DocumentParser.class),
            mock(CourseAiKeys.class), assets, bundles, mock(CourseAccess.class), mock(CurrentActor.class),
            new ObjectMapper(), Runnable::run, mock(TransactionTemplate.class));

    @Test
    void 新建行是parsing_解析完成置ready() {
        MaterialBundleEntity entity = new MaterialBundleEntity(6L, 10L, 1L, "讲义.pdf", "", "[]", "[]",
                LocalDateTime.now());
        assertThat(entity.getState()).isEqualTo(MaterialBundleEntity.STATE_PARSING);
        entity.ready();
        assertThat(entity.getState()).isEqualTo(MaterialBundleEntity.STATE_READY);
    }

    @Test
    void 未就绪的素材包按404_不喂给生成() {
        MaterialBundleEntity parsing = mock(MaterialBundleEntity.class);
        when(parsing.getCourseId()).thenReturn(6L);
        when(parsing.getCoursewareId()).thenReturn(10L);
        when(parsing.getState()).thenReturn(MaterialBundleEntity.STATE_PARSING);
        when(bundles.selectById(5L)).thenReturn(parsing);
        assertThatThrownBy(() -> service.load(6L, 10L, 5L))
                .isInstanceOf(NotFoundException.class).hasMessage("素材不存在");
    }

    @Test
    void 归属不符按404() {
        MaterialBundleEntity ready = mock(MaterialBundleEntity.class);
        when(ready.getCourseId()).thenReturn(6L);
        when(ready.getCoursewareId()).thenReturn(99L);
        when(ready.getState()).thenReturn(MaterialBundleEntity.STATE_READY);
        when(bundles.selectById(5L)).thenReturn(ready);
        assertThatThrownBy(() -> service.load(6L, 10L, 5L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void discard从行导出图片键入队_原件留给清理器() {
        MaterialBundleEntity entity = mock(MaterialBundleEntity.class);
        when(entity.getImagesJson()).thenReturn(
                "[{\"objectKey\":\"courseware/bundles/5/img_1.png\"},{\"objectKey\":\"\"}]");
        when(bundles.selectById(5L)).thenReturn(entity);

        service.discard(5L);

        verify(bundles).deleteById(5L);
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(assets).enqueueDelete(keys.capture());
        assertThat(keys.getValue()).containsExactly("courseware/bundles/5/img_1.png");
    }

    @Test
    void discard不存在的行_不做任何事() {
        when(bundles.selectById(404L)).thenReturn(null);
        service.discard(404L);
        verify(bundles, never()).deleteById(404L);
        verify(assets, never()).enqueueDelete(org.mockito.ArgumentMatchers.anyCollection());
    }
}
