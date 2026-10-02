package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 数据库不设外键:删课件先删素材包、浏览 / 问答 / 测验作答记录,最后删课件行 */
class CoursewareRowPurgerTest {

    private final CoursewareMapper coursewares = mock(CoursewareMapper.class);
    private final SceneViewEventMapper sceneViews = mock(SceneViewEventMapper.class);
    private final QaEventMapper qaEvents = mock(QaEventMapper.class);
    private final QuizAttemptMapper quizAttempts = mock(QuizAttemptMapper.class);
    private final MaterialBundleMapper bundles = mock(MaterialBundleMapper.class);
    private final CoursewareAssetStorage assetStorage = mock(CoursewareAssetStorage.class);
    private final CoursewareRowPurger purger = new CoursewareRowPurger(coursewares, sceneViews, qaEvents, quizAttempts,
            bundles, assetStorage,
            new com.fasterxml.jackson.databind.ObjectMapper());

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CoursewareEntity.class);
        TableInfoHelper.initTableInfo(assistant, SceneViewEventEntity.class);
        TableInfoHelper.initTableInfo(assistant, QaEventEntity.class);
        TableInfoHelper.initTableInfo(assistant, QuizAttemptEntity.class);
        TableInfoHelper.initTableInfo(assistant, MaterialBundleEntity.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void purgesChildRowsBeforeTheCoursewareRow() {
        purger.purgeCoursewares(List.of(21L));

        InOrder order = inOrder(bundles, sceneViews, qaEvents, quizAttempts, coursewares);
        order.verify(bundles).delete(any(Wrapper.class));
        order.verify(sceneViews).delete(any(Wrapper.class));
        order.verify(qaEvents).delete(any(Wrapper.class));
        order.verify(quizAttempts).delete(any(Wrapper.class));
        order.verify(coursewares).delete(any(Wrapper.class));
    }

    @Test
    void nothingHappensForNoCoursewares() {
        purger.purgeCoursewares(List.of());

        verify(coursewares, never()).delete(any());
    }
}
