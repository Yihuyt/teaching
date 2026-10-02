package cn.utcy.teaching.analytics.application;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.analytics.application.ProgressCalculator.ItemProgress;
import cn.utcy.teaching.analytics.application.ProgressCalculator.Progress;
import cn.utcy.teaching.analytics.application.ProgressCalculator.UnitProgress;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineItemView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineUnitView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按内容看全班:课程内容里每道试题 / 编程题一行——多少人作答、多少人完成、试题的平均最高得分率。
 * 同一内容被编排到多处只列一行,所属单元列出全部位置;完成口径与 {@link ProgressCalculator} 同一份(取自各人进度)。
 * 文件没有学习信号,不列。
 */
public final class ContentCalculator {

    private ContentCalculator() {
    }

    public record StudentContentStat(long accountId, boolean completed, int attempts,
                                     @Schema(nullable = true) Double bestScore) {
    }

    /**
     * @param units         所属单元的标题路径,每个位置一条(顶层位置为空串)
     * @param averageScore  试题:作答过的学生各自最高得分率的均值;编程题为 null
     * @param attemptCount  全班作答 / 提交总次数
     */
    public record ContentClassStat(CourseOutlineItemType itemType, long contentId, String title,
                                   List<String> units, int attemptedStudents, int completedStudents,
                                   @Schema(nullable = true) Double averageScore, long attemptCount,
                                   List<StudentContentStat> students) {
    }

    private static final class Accumulator {
        final CourseOutlineItemType itemType;
        final long contentId;
        final String title;
        final List<String> units = new ArrayList<>();
        final Map<Long, int[]> attemptsByAccount = new HashMap<>();
        final Map<Long, Double> bestByAccount = new HashMap<>();

        Accumulator(CourseOutlineItemView item) {
            this.itemType = item.itemType();
            this.contentId = item.contentId();
            this.title = item.title();
        }
    }

    /**
     * @param eventsByAccount 各学生的全部课程事件(按时间升序)
     * @param progressByAccount 各学生的进度(完成判定的唯一来源)
     */
    public static List<ContentClassStat> compute(CourseOutlineView outline, List<Long> accountIds,
                                                 Map<Long, List<AnalyticsEvent>> eventsByAccount,
                                                 Map<Long, Progress> progressByAccount) {
        Map<String, Accumulator> byContent = new LinkedHashMap<>();
        for (CourseOutlineItemView item : outline.items()) {
            place(byContent, item, "");
        }
        visit(outline.units(), "", byContent);

        for (Long accountId : accountIds) {
            for (AnalyticsEvent event : eventsByAccount.getOrDefault(accountId, List.of())) {
                switch (event.type()) {
                    case QUESTION_ATTEMPTED -> {
                        Accumulator acc = byContent.get(key(CourseOutlineItemType.QUESTION, event.objectId()));
                        if (acc != null) {
                            acc.attemptsByAccount.computeIfAbsent(accountId, id -> new int[1])[0]++;
                            acc.bestByAccount.merge(accountId, MasteryCalculator.attemptRatio(
                                    event.detailDouble("score"), event.detailDouble("totalScore")), Math::max);
                        }
                    }
                    case PROGRAMMING_JUDGED -> {
                        Accumulator acc = byContent.get(
                                key(CourseOutlineItemType.PROGRAMMING_PROBLEM, event.objectId()));
                        if (acc != null) {
                            acc.attemptsByAccount.computeIfAbsent(accountId, id -> new int[1])[0]++;
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        Map<Long, Map<String, Boolean>> completedByAccount = new HashMap<>();
        for (Long accountId : accountIds) {
            Map<String, Boolean> completed = new HashMap<>();
            Progress progress = progressByAccount.get(accountId);
            if (progress == null) {
                throw new IllegalStateException("缺少学生进度：" + accountId);
            }
            for (ItemProgress item : progress.items()) {
                completed.merge(key(item.itemType(), item.contentId()), item.completed(), Boolean::logicalOr);
            }
            for (UnitProgress unit : progress.units()) {
                for (ItemProgress item : unit.items()) {
                    completed.merge(key(item.itemType(), item.contentId()), item.completed(), Boolean::logicalOr);
                }
            }
            completedByAccount.put(accountId, completed);
        }

        List<ContentClassStat> stats = new ArrayList<>();
        for (Map.Entry<String, Accumulator> entry : byContent.entrySet()) {
            Accumulator acc = entry.getValue();
            List<StudentContentStat> students = new ArrayList<>();
            int attempted = 0;
            int completedCount = 0;
            long attemptCount = 0;
            double bestSum = 0;
            int bestCount = 0;
            for (Long accountId : accountIds) {
                int attempts = acc.attemptsByAccount.getOrDefault(accountId, new int[1])[0];
                Double best = acc.itemType == CourseOutlineItemType.QUESTION
                        ? acc.bestByAccount.get(accountId) : null;
                boolean completed = completedByAccount.get(accountId).getOrDefault(entry.getKey(), false);
                if (attempts > 0) {
                    attempted++;
                }
                if (completed) {
                    completedCount++;
                }
                if (best != null) {
                    bestSum += best;
                    bestCount++;
                }
                attemptCount += attempts;
                students.add(new StudentContentStat(accountId, completed, attempts, best));
            }
            stats.add(new ContentClassStat(acc.itemType, acc.contentId, acc.title, List.copyOf(acc.units),
                    attempted, completedCount, bestCount == 0 ? null : bestSum / bestCount, attemptCount,
                    students));
        }
        return stats;
    }

    private static void visit(List<CourseOutlineUnitView> units, String parentPath,
                              Map<String, Accumulator> byContent) {
        for (CourseOutlineUnitView unit : units) {
            String path = parentPath.isEmpty() ? unit.title() : parentPath + " / " + unit.title();
            for (CourseOutlineItemView item : unit.items()) {
                place(byContent, item, path);
            }
            visit(unit.children(), path, byContent);
        }
    }

    private static void place(Map<String, Accumulator> byContent, CourseOutlineItemView item, String path) {
        if (item.itemType() == CourseOutlineItemType.MATERIAL) {
            return;
        }
        byContent.computeIfAbsent(key(item.itemType(), item.contentId()), k -> new Accumulator(item))
                .units.add(path);
    }

    private static String key(CourseOutlineItemType type, long contentId) {
        return type + ":" + contentId;
    }
}
