package cn.utcy.teaching.analytics.application;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.analytics.application.ContentCalculator.ContentClassStat;
import cn.utcy.teaching.analytics.application.ProgressCalculator.Progress;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineItemView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineUnitView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContentCalculatorTest {

    private final ObjectMapper json = new ObjectMapper();

    private AnalyticsEvent event(long accountId, LearningEventType type, long objectId, Map<String, Object> detail) {
        return new AnalyticsEvent(accountId, type, objectId, json.valueToTree(detail), Instant.EPOCH);
    }

    private static CourseOutlineItemView item(long id, CourseOutlineItemType type, long contentId, String title) {
        return new CourseOutlineItemView(id, type, contentId, title, (int) id);
    }

    @Test
    void 同一内容多处编排合并为一行_文件不列_完成口径取自进度() {
        CourseOutlineView outline = new CourseOutlineView(
                List.of(item(1, CourseOutlineItemType.QUESTION, 20, "方程"),
                        item(2, CourseOutlineItemType.MATERIAL, 10, "讲义")),
                List.of(new CourseOutlineUnitView(1L, "第一章", 1,
                        List.of(new CourseOutlineUnitView(2L, "第一节", 1, List.of(),
                                List.of(item(3, CourseOutlineItemType.QUESTION, 20, "方程")))),
                        List.of(item(4, CourseOutlineItemType.PROGRAMMING_PROBLEM, 30, "A+B")))));
        Map<Long, List<AnalyticsEvent>> events = Map.of(
                7L, List.of(
                        event(7, LearningEventType.QUESTION_ATTEMPTED, 20, Map.of("score", 2, "totalScore", 10)),
                        event(7, LearningEventType.QUESTION_ATTEMPTED, 20, Map.of("score", 6, "totalScore", 10)),
                        event(7, LearningEventType.PROGRAMMING_JUDGED, 30, Map.of("accepted", false))),
                8L, List.of(
                        event(8, LearningEventType.QUESTION_ATTEMPTED, 20, Map.of("score", 10, "totalScore", 10))));
        List<Long> accounts = List.of(7L, 8L, 9L);
        Map<Long, Progress> progress = new HashMap<>();
        for (Long accountId : accounts) {
            progress.put(accountId, ProgressCalculator.compute(outline, events.getOrDefault(accountId, List.of())));
        }

        List<ContentClassStat> stats = ContentCalculator.compute(outline, accounts, events, progress);

        assertThat(stats).extracting(ContentClassStat::title).containsExactly("方程", "A+B");
        ContentClassStat question = stats.get(0);
        assertThat(question.units()).containsExactly("", "第一章 / 第一节");
        assertThat(question.attemptedStudents()).isEqualTo(2);
        assertThat(question.completedStudents()).isEqualTo(2);
        assertThat(question.attemptCount()).isEqualTo(3);
        assertThat(question.averageScore()).isEqualTo((0.6 + 1.0) / 2);
        assertThat(question.students()).extracting(s -> s.accountId(), s -> s.completed(), s -> s.attempts(), s -> s.bestScore())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(7L, true, 2, 0.6),
                        org.assertj.core.groups.Tuple.tuple(8L, true, 1, 1.0),
                        org.assertj.core.groups.Tuple.tuple(9L, false, 0, null));
        ContentClassStat problem = stats.get(1);
        assertThat(problem.units()).containsExactly("第一章");
        assertThat(problem.attemptedStudents()).isEqualTo(1);
        // 编程题未通过=未完成;没有得分率
        assertThat(problem.completedStudents()).isZero();
        assertThat(problem.averageScore()).isNull();
    }
}
