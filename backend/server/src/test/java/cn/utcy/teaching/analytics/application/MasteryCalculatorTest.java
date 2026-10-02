package cn.utcy.teaching.analytics.application;

import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.analytics.application.MasteryCalculator.Level;
import cn.utcy.teaching.analytics.application.MasteryCalculator.NodeMastery;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.AnalyticsSnapshot;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.PrerequisiteEdge;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.SnapshotNode;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.SnapshotResource;
import cn.utcy.teaching.knowledgegraph.domain.NodeKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MasteryCalculatorTest {

    private final ObjectMapper json = new ObjectMapper();
    private Instant clock = Instant.parse("2026-08-21T00:00:00Z");

    private AnalyticsEvent event(LearningEventType type, long objectId, Map<String, Object> detail) {
        clock = clock.plusSeconds(60);
        return new AnalyticsEvent(1L, type, objectId, json.valueToTree(detail), clock);
    }

    private static SnapshotNode kp(long id, SnapshotResource... resources) {
        return new SnapshotNode(1L, "图谱", id, "节点" + id, NodeKind.KNOWLEDGE_POINT, List.of(resources));
    }

    private static SnapshotResource question(long id) {
        return new SnapshotResource(CourseOutlineItemType.QUESTION, id, "题" + id);
    }

    private static SnapshotResource problem(long id) {
        return new SnapshotResource(CourseOutlineItemType.PROGRAMMING_PROBLEM, id, "编程题" + id);
    }

    @Test
    void 得分率按得分除以总分_总分为零记零() {
        assertThat(MasteryCalculator.attemptRatio(8, 10)).isEqualTo(0.8);
        assertThat(MasteryCalculator.attemptRatio(0, 10)).isEqualTo(0.0);
        assertThat(MasteryCalculator.attemptRatio(5, 0)).isEqualTo(0.0);
    }

    @Test
    void 多次交卷取最高得分率() {
        AnalyticsSnapshot snapshot = new AnalyticsSnapshot(List.of(kp(1, question(1))), List.of());
        List<AnalyticsEvent> events = List.of(
                event(LearningEventType.QUESTION_ATTEMPTED, 1, Map.of("score", 2, "totalScore", 10)),
                event(LearningEventType.QUESTION_ATTEMPTED, 1, Map.of("score", 9, "totalScore", 10)));

        assertThat(MasteryCalculator.compute(snapshot, events).get(0).score()).isCloseTo(0.9, within(1e-9));
    }

    @Test
    void 只有试题时权重归一化为纯试题分() {
        AnalyticsSnapshot snapshot = new AnalyticsSnapshot(List.of(kp(1, question(1), question(2))), List.of());
        List<AnalyticsEvent> events = List.of(
                event(LearningEventType.QUESTION_ATTEMPTED, 1, Map.of("score", 10, "totalScore", 10)),
                event(LearningEventType.QUESTION_ATTEMPTED, 2, Map.of("score", 0, "totalScore", 10)));

        NodeMastery node = MasteryCalculator.compute(snapshot, events).get(0);

        assertThat(node.score()).isCloseTo(0.5, within(1e-9));
        assertThat(node.level()).isEqualTo(Level.BASIC);
    }

    @Test
    void 试题与编程题按零点五比零点三加权() {
        AnalyticsSnapshot snapshot = new AnalyticsSnapshot(List.of(kp(1, question(1), problem(9))), List.of());
        List<AnalyticsEvent> events = List.of(
                event(LearningEventType.QUESTION_ATTEMPTED, 1, Map.of("score", 10, "totalScore", 10)),
                event(LearningEventType.PROGRAMMING_JUDGED, 9, Map.of("accepted", false)));

        NodeMastery node = MasteryCalculator.compute(snapshot, events).get(0);

        // (0.5*1 + 0.3*0) / 0.8
        assertThat(node.score()).isCloseTo(0.625, within(1e-9));
    }

    @Test
    void 未作答但打开过挂载资料算已接触() {
        SnapshotNode node = kp(1, new SnapshotResource(CourseOutlineItemType.MATERIAL, 7, "讲义"), question(1));
        AnalyticsSnapshot snapshot = new AnalyticsSnapshot(List.of(node), List.of());

        NodeMastery untouched = MasteryCalculator.compute(snapshot, List.of()).get(0);
        NodeMastery touched = MasteryCalculator.compute(snapshot,
                List.of(event(LearningEventType.KG_RESOURCE_OPENED, 1, Map.of("nodeId", 1)))).get(0);

        assertThat(untouched.level()).isEqualTo(Level.UNTOUCHED);
        assertThat(untouched.score()).isNull();
        assertThat(touched.level()).isEqualTo(Level.TOUCHED);
    }

    @Test
    void 薄弱节点的前置不薄弱才是根因() {
        // base 是 mid 的前置,mid 是 top 的前置;base 掌握、mid/top 薄弱 → 根因只有 mid
        AnalyticsSnapshot snapshot = new AnalyticsSnapshot(
                List.of(kp(1, question(1)), kp(2, question(2)), kp(3, question(3))),
                List.of(new PrerequisiteEdge(1L, 1L, 2L), new PrerequisiteEdge(1L, 2L, 3L)));
        List<AnalyticsEvent> events = List.of(
                event(LearningEventType.QUESTION_ATTEMPTED, 1, Map.of("score", 10, "totalScore", 10)),
                event(LearningEventType.QUESTION_ATTEMPTED, 2, Map.of("score", 0, "totalScore", 10)),
                event(LearningEventType.QUESTION_ATTEMPTED, 3, Map.of("score", 0, "totalScore", 10)));

        List<NodeMastery> result = MasteryCalculator.compute(snapshot, events);

        assertThat(result).extracting(NodeMastery::nodeId, NodeMastery::level, NodeMastery::rootCause)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1L, Level.PROFICIENT, false),
                        org.assertj.core.groups.Tuple.tuple(2L, Level.WEAK, true),
                        org.assertj.core.groups.Tuple.tuple(3L, Level.WEAK, false));
    }

    @Test
    void 非知识点且无挂载的节点不进掌握度() {
        AnalyticsSnapshot snapshot = new AnalyticsSnapshot(List.of(
                new SnapshotNode(1L, "图谱", 7L, "第一章", NodeKind.UNIT, List.of()),
                new SnapshotNode(1L, "图谱", 8L, "第二章", NodeKind.UNIT, List.of(question(1))),
                kp(1)), List.of());

        List<NodeMastery> result = MasteryCalculator.compute(snapshot, List.of());

        assertThat(result).extracting(NodeMastery::nodeId).containsExactly(8L, 1L);
    }
}
