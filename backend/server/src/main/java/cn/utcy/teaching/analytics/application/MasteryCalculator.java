package cn.utcy.teaching.analytics.application;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.AnalyticsSnapshot;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.PrerequisiteEdge;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.SnapshotNode;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.SnapshotResource;
import cn.utcy.teaching.knowledgegraph.domain.NodeKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识点掌握度:以图谱节点为单位,分数来自节点挂载的资源上的行为——
 * 试题(权重 0.5):取该卷各次交卷得分率的最高值(得分 / 总分),未交卷不计;
 * 编程题(权重 0.3):有通过=1,判过未过=0,未判不计;
 * 缺项按剩余权重归一化。资料只算"接触"。
 * 档位:未接触 / 已接触 / 薄弱(<0.4)/ 基本掌握(<0.75)/ 熟练。
 * 根因:薄弱且其全部前置都不薄弱。
 */
public final class MasteryCalculator {

    public static final double QUESTION_WEIGHT = 0.5;
    public static final double PROGRAMMING_WEIGHT = 0.3;
    public static final double WEAK_THRESHOLD = 0.4;
    public static final double BASIC_THRESHOLD = 0.75;
    private MasteryCalculator() {
    }

    public enum Level {
        UNTOUCHED, TOUCHED, WEAK, BASIC, PROFICIENT
    }

    public record NodeMastery(long graphId, String graphName, long nodeId, String label, NodeKind kind,
                              Double score, Level level, boolean rootCause,
                              List<SnapshotResource> resources) {

        public String key() {
            return graphId + ":" + nodeId;
        }
    }

    static double attemptRatio(double score, double totalScore) {
        return totalScore <= 0 ? 0 : Math.max(0, Math.min(1, score / totalScore));
    }

    public static Level levelOf(Double score, boolean touched) {
        if (score == null) {
            return touched ? Level.TOUCHED : Level.UNTOUCHED;
        }
        if (score < WEAK_THRESHOLD) {
            return Level.WEAK;
        }
        if (score < BASIC_THRESHOLD) {
            return Level.BASIC;
        }
        return Level.PROFICIENT;
    }

    public static List<NodeMastery> compute(AnalyticsSnapshot snapshot, List<AnalyticsEvent> events) {
        // 事件按对象归档(事件列表已按时间升序)
        Map<Long, Double> questionBest = new HashMap<>();
        Map<Long, Boolean> problemAccepted = new HashMap<>();
        Set<String> touchedNodes = new HashSet<>();
        for (AnalyticsEvent event : events) {
            switch (event.type()) {
                case QUESTION_ATTEMPTED -> questionBest.merge(event.objectId(),
                        attemptRatio(event.detailDouble("score"), event.detailDouble("totalScore")), Math::max);
                case PROGRAMMING_JUDGED -> problemAccepted.merge(
                        event.objectId(), event.detailBoolean("accepted"), Boolean::logicalOr);
                case KG_RESOURCE_OPENED -> touchedNodes.add(event.objectId() + ":" + event.detailLong("nodeId"));
                default -> {
                }
            }
        }

        Map<String, NodeMastery> byKey = new LinkedHashMap<>();
        for (SnapshotNode node : snapshot.nodes()) {
            if (node.kind() != NodeKind.KNOWLEDGE_POINT && node.resources().isEmpty()) {
                continue;
            }
            double questionSum = 0;
            int questionCount = 0;
            double problemSum = 0;
            int problemCount = 0;
            boolean touched = touchedNodes.contains(node.graphId() + ":" + node.nodeId());
            for (SnapshotResource resource : node.resources()) {
                if (resource.itemType() == CourseOutlineItemType.QUESTION) {
                    Double best = questionBest.get(resource.contentId());
                    if (best != null) {
                        questionSum += best;
                        questionCount++;
                    }
                } else if (resource.itemType() == CourseOutlineItemType.PROGRAMMING_PROBLEM) {
                    Boolean accepted = problemAccepted.get(resource.contentId());
                    if (accepted != null) {
                        problemSum += accepted ? 1.0 : 0.0;
                        problemCount++;
                    }
                }
            }
            Double score = null;
            double weight = 0;
            double weighted = 0;
            if (questionCount > 0) {
                weighted += QUESTION_WEIGHT * (questionSum / questionCount);
                weight += QUESTION_WEIGHT;
            }
            if (problemCount > 0) {
                weighted += PROGRAMMING_WEIGHT * (problemSum / problemCount);
                weight += PROGRAMMING_WEIGHT;
            }
            if (weight > 0) {
                score = weighted / weight;
            }
            NodeMastery mastery = new NodeMastery(node.graphId(), node.graphName(), node.nodeId(),
                    node.label(), node.kind(), score, levelOf(score, touched), false, node.resources());
            byKey.put(mastery.key(), mastery);
        }

        Map<String, List<String>> prerequisitesOf = new HashMap<>();
        for (PrerequisiteEdge edge : snapshot.prerequisiteEdges()) {
            prerequisitesOf.computeIfAbsent(edge.graphId() + ":" + edge.targetNodeId(), id -> new ArrayList<>())
                    .add(edge.graphId() + ":" + edge.sourceNodeId());
        }
        List<NodeMastery> result = new ArrayList<>();
        for (NodeMastery mastery : byKey.values()) {
            boolean rootCause = mastery.level() == Level.WEAK
                    && prerequisitesOf.getOrDefault(mastery.key(), List.of()).stream()
                    .map(byKey::get)
                    .noneMatch(prerequisite -> prerequisite != null && prerequisite.level() == Level.WEAK);
            result.add(new NodeMastery(mastery.graphId(), mastery.graphName(), mastery.nodeId(),
                    mastery.label(), mastery.kind(), mastery.score(), mastery.level(), rootCause,
                    mastery.resources()));
        }
        return result;
    }

    public static Double average(List<NodeMastery> nodes) {
        double sum = 0;
        int count = 0;
        for (NodeMastery node : nodes) {
            if (node.score() != null) {
                sum += node.score();
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    public static long weakCount(List<NodeMastery> nodes) {
        return nodes.stream().filter(node -> node.level() == Level.WEAK).count();
    }
}
