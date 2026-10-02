package cn.utcy.teaching.analytics.application;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.analytics.application.ProgressCalculator.Progress;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineUnitView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineItemView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressCalculatorTest {

    private final ObjectMapper json = new ObjectMapper();

    private AnalyticsEvent event(LearningEventType type, long objectId, Map<String, Object> detail) {
        return new AnalyticsEvent(1L, type, objectId, json.valueToTree(detail), Instant.EPOCH);
    }

    private static CourseOutlineItemView item(long id, CourseOutlineItemType type, long contentId) {
        return new CourseOutlineItemView(id, type, contentId, "内容" + id, (int) id);
    }

    @Test
    void 三类内容各按口径判定完成_单元按深度展平() {
        CourseOutlineView outline = new CourseOutlineView(
                List.of(item(9, CourseOutlineItemType.MATERIAL, 12)),
                List.of(
                new CourseOutlineUnitView(1L, "第一章", 1,
                        List.of(new CourseOutlineUnitView(2L, "第一节", 1, List.of(),
                                List.of(item(4, CourseOutlineItemType.MATERIAL, 11)))),
                        List.of(item(1, CourseOutlineItemType.MATERIAL, 10),
                                item(2, CourseOutlineItemType.QUESTION, 20),
                                item(3, CourseOutlineItemType.PROGRAMMING_PROBLEM, 30)))));
        List<AnalyticsEvent> events = List.of(
                event(LearningEventType.QUESTION_ATTEMPTED, 20, Map.of("correct", false)),
                event(LearningEventType.PROGRAMMING_JUDGED, 30, Map.of("accepted", false)),
                event(LearningEventType.COURSEWARE_SCENE_VIEWED, 40, Map.of("sceneId", "p1")));

        Progress progress = ProgressCalculator.compute(outline, events);

        // 文件不参与进度(顶层与单元里的文件都不算);试题作答=完成(不论对错);编程题未通过=未完成
        assertThat(progress.totalItems()).isEqualTo(2);
        assertThat(progress.completedItems()).isEqualTo(1);
        assertThat(progress.items()).isEmpty();
        assertThat(progress.units()).extracting(c -> c.title(), c -> c.depth(), c -> c.items().size())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("第一章", 0, 2),
                        org.assertj.core.groups.Tuple.tuple("第一节", 1, 0));
        assertThat(progress.units().get(0).items()).extracting(i -> i.completed()).containsExactly(true, false);
    }
}
