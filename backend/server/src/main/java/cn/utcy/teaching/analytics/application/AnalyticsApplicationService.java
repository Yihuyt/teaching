package cn.utcy.teaching.analytics.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.analytics.application.ContentCalculator.ContentClassStat;
import cn.utcy.teaching.analytics.application.MasteryCalculator.Level;
import cn.utcy.teaching.analytics.application.MasteryCalculator.NodeMastery;
import cn.utcy.teaching.analytics.application.ProgressCalculator.Progress;
import cn.utcy.teaching.analytics.infrastructure.LearningEventEntity;
import cn.utcy.teaching.analytics.infrastructure.LearningEventMapper;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.course.application.CourseMemberApplicationService;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineView;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.AnalyticsSnapshot;
import cn.utcy.teaching.knowledgegraph.domain.NodeKind;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学情读模型:一次拉取课程全部学习事件,按学生切分后交三个纯计算器,
 * 教师看全班/任意学生,学生只看自己(附匿名班级位置)。实时聚合,不做预计算表。
 */
@Service
public class AnalyticsApplicationService {

    private final LearningEventMapper events;
    private final CourseAccess courseAccess;
    private final CourseMemberApplicationService members;
    private final CourseOutlineApplicationService outlines;
    private final KnowledgeGraphService graphs;
    private final AccountDirectory accounts;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;

    public AnalyticsApplicationService(LearningEventMapper events,
                                       CourseAccess courseAccess,
                                       CourseMemberApplicationService members,
                                       CourseOutlineApplicationService outlines,
                                       KnowledgeGraphService graphs,
                                       AccountDirectory accounts,
                                       CurrentActor currentActor,
                                       ObjectMapper objectMapper) {
        this.events = events;
        this.courseAccess = courseAccess;
        this.members = members;
        this.outlines = outlines;
        this.graphs = graphs;
        this.accounts = accounts;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
    }

    /* ---------- 视图 ---------- */

    public record StudentSummary(long accountId, String displayName, String username,
                                 double progressPercent, @Schema(nullable = true) Double masteryAverage,
                                 long weakCount, boolean needsAttention) {
    }

    /**
     * @param assessable 挂了试题 / 编程题,能算出分数;否则只有"接触过与否"
     * @param basicStudents / proficientStudents / touchedStudents 各档位人数(与 weak / untouched 合计为学生数)
     */
    public record NodeClassStat(long graphId, String graphName, long nodeId, String label, NodeKind kind,
                                boolean assessable, @Schema(nullable = true) Double averageScore,
                                int scoredStudents, int weakStudents, int basicStudents, int proficientStudents,
                                int touchedStudents, int untouchedStudents) {
    }

    public record ClassOverview(int studentCount, double averageProgress,
                                @Schema(nullable = true) Double averageMastery,
                                List<StudentSummary> students, List<ContentClassStat> contents,
                                List<NodeClassStat> nodes) {
    }

    public record ResourceRef(CourseOutlineItemType itemType, long contentId, String title) {
    }

    public record NodeMasteryView(long graphId, String graphName, long nodeId, String label, NodeKind kind,
                                  @Schema(nullable = true) Double score, Level level, boolean rootCause,
                                  List<ResourceRef> resources) {
    }

    /** 学生相对班级的位置(匿名):TOP(前 30%)/ MIDDLE / BOTTOM(后 30%);班级无数据为 null */
    public enum ClassPosition {
        TOP, MIDDLE, BOTTOM
    }

    public record StudentReport(long accountId, String displayName, Progress progress,
                                List<NodeMasteryView> mastery, @Schema(nullable = true) Double masteryAverage,
                                long weakCount, @Schema(nullable = true) ClassPosition classPosition) {
    }

    /* ---------- 入口 ---------- */

    @Transactional(readOnly = true)
    public ClassOverview overview(long courseId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return buildOverview(courseId);
    }

    @Transactional(readOnly = true)
    public StudentReport studentReport(long courseId, long accountId, boolean management) {
        Actor actor = currentActor.require();
        if (management) {
            courseAccess.requireManagementAccess(courseId, actor);
        } else {
            courseAccess.requireLearningAccess(courseId, actor);
            if (actor.userId() != accountId) {
                throw new ForbiddenOperationException("只能查看自己的学情");
            }
        }
        CourseContext context = loadContext(courseId);
        StudentComputation mine = compute(context, accountId);
        ClassPosition position = null;
        if (!management) {
            position = classPosition(context, accountId, mine);
        }
        return report(accountId, mine, position);
    }

    /** 导出与 AI 简报复用的全班计算(调用方已鉴权) */
    ClassOverview buildOverview(long courseId) {
        CourseContext context = loadContext(courseId);
        List<StudentComputation> computations = new ArrayList<>();
        for (Long accountId : context.studentIds()) {
            computations.add(compute(context, accountId));
        }

        // 需关注:薄弱节点数达到全班 P75(至少 1)
        List<Long> weakCounts = computations.stream()
                .map(c -> MasteryCalculator.weakCount(c.mastery()))
                .sorted()
                .toList();
        long weakThreshold = weakCounts.isEmpty() ? Long.MAX_VALUE
                : Math.max(1, weakCounts.get((int) Math.floor((weakCounts.size() - 1) * 0.75)));

        List<StudentSummary> summaries = new ArrayList<>();
        double progressSum = 0;
        double masterySum = 0;
        int masteryCount = 0;
        for (StudentComputation c : computations) {
            long weak = MasteryCalculator.weakCount(c.mastery());
            Double masteryAverage = MasteryCalculator.average(c.mastery());
            boolean attention = weak > 0 && weak >= weakThreshold;
            progressSum += c.progress().percent();
            if (masteryAverage != null) {
                masterySum += masteryAverage;
                masteryCount++;
            }
            AccountDirectory.AccountSummary account = context.accounts().get(c.accountId());
            summaries.add(new StudentSummary(c.accountId(), account.name(), account.username(),
                    c.progress().percent(), masteryAverage, weak, attention));
        }
        summaries.sort(Comparator.comparing(StudentSummary::needsAttention).reversed()
                .thenComparing(StudentSummary::displayName));

        return new ClassOverview(
                computations.size(),
                computations.isEmpty() ? 0 : progressSum / computations.size(),
                masteryCount == 0 ? null : masterySum / masteryCount,
                summaries,
                contentClassStats(context, computations),
                nodeClassStats(computations));
    }

    StudentReport studentReportForBrief(long courseId, long accountId) {
        CourseContext context = loadContext(courseId);
        StudentComputation mine = compute(context, accountId);
        return report(accountId, mine, classPosition(context, accountId, mine));
    }

    /* ---------- 计算 ---------- */

    record CourseContext(long courseId, List<Long> studentIds,
                         Map<Long, AccountDirectory.AccountSummary> accounts,
                         Map<Long, List<AnalyticsEvent>> eventsByAccount,
                         CourseOutlineView outline,
                         AnalyticsSnapshot snapshot) {
    }

    record StudentComputation(long accountId, Progress progress, List<NodeMastery> mastery) {
    }

    private CourseContext loadContext(long courseId) {
        List<Long> studentIds = new ArrayList<>();
        Map<Long, AccountDirectory.AccountSummary> accountMap = new LinkedHashMap<>();
        for (Long accountId : members.memberAccountIds(courseId)) {
            AccountDirectory.AccountSummary account = accounts.require(accountId);
            studentIds.add(accountId);
            accountMap.put(accountId, account);
        }
        Map<Long, List<AnalyticsEvent>> byAccount = new HashMap<>();
        for (LearningEventEntity entity : events.selectList(new LambdaQueryWrapper<LearningEventEntity>()
                .eq(LearningEventEntity::getCourseId, courseId)
                .orderByAsc(LearningEventEntity::getOccurredAt)
                .orderByAsc(LearningEventEntity::getId))) {
            byAccount.computeIfAbsent(entity.getAccountId(), id -> new ArrayList<>())
                    .add(toEvent(entity));
        }
        return new CourseContext(courseId, studentIds, accountMap, byAccount,
                outlines.getTrusted(courseId),
                graphs.analyticsSnapshot(courseId));
    }

    private StudentComputation compute(CourseContext context, long accountId) {
        List<AnalyticsEvent> mine = context.eventsByAccount().getOrDefault(accountId, List.of());
        return new StudentComputation(accountId,
                ProgressCalculator.compute(context.outline(), mine),
                MasteryCalculator.compute(context.snapshot(), mine));
    }

    private ClassPosition classPosition(CourseContext context, long accountId, StudentComputation mine) {
        Double myAverage = MasteryCalculator.average(mine.mastery());
        if (myAverage == null) {
            return null;
        }
        List<Double> others = new ArrayList<>();
        for (Long studentId : context.studentIds()) {
            Double average = studentId == accountId ? myAverage
                    : MasteryCalculator.average(compute(context, studentId).mastery());
            if (average != null) {
                others.add(average);
            }
        }
        if (others.size() < 2) {
            return null;
        }
        long below = others.stream().filter(score -> score < myAverage).count();
        double percentile = (double) below / (others.size() - 1);
        if (percentile >= 0.7) {
            return ClassPosition.TOP;
        }
        if (percentile < 0.3) {
            return ClassPosition.BOTTOM;
        }
        return ClassPosition.MIDDLE;
    }

    private StudentReport report(long accountId, StudentComputation c, ClassPosition position) {
        AccountDirectory.AccountSummary account = accounts.require(accountId);
        List<NodeMasteryView> mastery = c.mastery().stream()
                .map(node -> new NodeMasteryView(node.graphId(), node.graphName(), node.nodeId(),
                        node.label(), node.kind(), node.score(), node.level(), node.rootCause(),
                        node.resources().stream()
                                .map(r -> new ResourceRef(r.itemType(), r.contentId(), r.title()))
                                .toList()))
                .toList();
        return new StudentReport(accountId, account.name(), c.progress(), mastery,
                MasteryCalculator.average(c.mastery()), MasteryCalculator.weakCount(c.mastery()), position);
    }

    private List<ContentClassStat> contentClassStats(CourseContext context,
                                                     List<StudentComputation> computations) {
        Map<Long, Progress> progressByAccount = new HashMap<>();
        for (StudentComputation c : computations) {
            progressByAccount.put(c.accountId(), c.progress());
        }
        return ContentCalculator.compute(context.outline(), context.studentIds(),
                context.eventsByAccount(), progressByAccount);
    }

    private List<NodeClassStat> nodeClassStats(List<StudentComputation> computations) {
        Map<String, List<NodeMastery>> byNode = new LinkedHashMap<>();
        for (StudentComputation c : computations) {
            for (NodeMastery node : c.mastery()) {
                byNode.computeIfAbsent(node.key(), key -> new ArrayList<>()).add(node);
            }
        }
        List<NodeClassStat> stats = new ArrayList<>();
        for (List<NodeMastery> nodes : byNode.values()) {
            NodeMastery sample = nodes.get(0);
            double sum = 0;
            int scored = 0;
            int weak = 0;
            int basic = 0;
            int proficient = 0;
            int touched = 0;
            int untouched = 0;
            for (NodeMastery node : nodes) {
                if (node.score() != null) {
                    sum += node.score();
                    scored++;
                }
                switch (node.level()) {
                    case WEAK -> weak++;
                    case BASIC -> basic++;
                    case PROFICIENT -> proficient++;
                    case TOUCHED -> touched++;
                    case UNTOUCHED -> untouched++;
                }
            }
            boolean assessable = sample.resources().stream()
                    .anyMatch(r -> r.itemType() == CourseOutlineItemType.QUESTION
                            || r.itemType() == CourseOutlineItemType.PROGRAMMING_PROBLEM);
            stats.add(new NodeClassStat(sample.graphId(), sample.graphName(), sample.nodeId(),
                    sample.label(), sample.kind(), assessable, scored == 0 ? null : sum / scored, scored,
                    weak, basic, proficient, touched, untouched));
        }
        return stats;
    }

    private AnalyticsEvent toEvent(LearningEventEntity entity) {
        JsonNode detail = null;
        if (entity.getDetail() != null) {
            try {
                detail = objectMapper.readTree(entity.getDetail());
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("学习事件附加数据不是有效 JSON：" + entity.getId(), exception);
            }
        }
        return new AnalyticsEvent(entity.getAccountId(), LearningEventType.fromValue(entity.getEventType()),
                entity.getObjectId(), detail, entity.getOccurredAt().toInstant(ZoneOffset.UTC));
    }

}
