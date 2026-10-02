package cn.utcy.teaching.analytics.application;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineUnitView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineItemView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 进度:课程内容逐项完成判定——试题=交过卷;编程题=有通过的判题;文件不计入进度
 * (打开文件不是学习行为的证据,只有作答与提交才算)。
 * 同一内容可被编排到多处:逐条列出便于按位置查看,但总数与完成数按**内容**去重,不重复计。
 * 单元按 DFS 展平(带深度)。课件不在课程内容里(自成「智能课堂」栏目),其学习情况由课件学习报告承载。
 */
public final class ProgressCalculator {

    private ProgressCalculator() {
    }

    public record ItemProgress(long itemId, CourseOutlineItemType itemType, long contentId,
                               String title, boolean completed) {
    }

    /** 单元只是分组:逐项进度按所属单元列出,不另算单元的完成比例 */
    public record UnitProgress(long unitId, String title, int depth, List<ItemProgress> items) {
    }

    /** items = 顶层内容(不属于任何单元)的逐项进度 */
    public record Progress(int totalItems, int completedItems, List<ItemProgress> items,
                           List<UnitProgress> units) {

        public double percent() {
            return totalItems == 0 ? 0 : (double) completedItems / totalItems;
        }
    }

    public static Progress compute(CourseOutlineView outline, List<AnalyticsEvent> events) {
        Set<Long> attemptedQuestions = new HashSet<>();
        Set<Long> acceptedProblems = new HashSet<>();
        for (AnalyticsEvent event : events) {
            switch (event.type()) {
                case QUESTION_ATTEMPTED -> attemptedQuestions.add(event.objectId());
                case PROGRAMMING_JUDGED -> {
                    if (event.detailBoolean("accepted")) {
                        acceptedProblems.add(event.objectId());
                    }
                }
                default -> {
                }
            }
        }

        Completion completion = item -> switch (item.itemType()) {
            case QUESTION -> attemptedQuestions.contains(item.contentId());
            case PROGRAMMING_PROBLEM -> acceptedProblems.contains(item.contentId());
            case MATERIAL -> throw new IllegalStateException("文件不参与进度");
        };
        List<ItemProgress> topItems = new ArrayList<>();
        Set<String> all = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (CourseOutlineItemView item : outline.items()) {
            if (item.itemType() == CourseOutlineItemType.MATERIAL) {
                continue;
            }
            boolean completed = completion.test(item);
            String key = contentKey(item);
            all.add(key);
            if (completed) {
                done.add(key);
            }
            topItems.add(new ItemProgress(item.id(), item.itemType(), item.contentId(), item.title(), completed));
        }
        List<UnitProgress> units = new ArrayList<>();
        visit(outline.units(), 0, units, all, done, completion);
        return new Progress(all.size(), done.size(), topItems, units);
    }

    private interface Completion {
        boolean test(CourseOutlineItemView item);
    }

    private static String contentKey(CourseOutlineItemView item) {
        return item.itemType() + ":" + item.contentId();
    }

    private static void visit(List<CourseOutlineUnitView> units, int depth,
                              List<UnitProgress> out, Set<String> all, Set<String> done,
                              Completion completion) {
        for (CourseOutlineUnitView unit : units) {
            List<ItemProgress> items = new ArrayList<>();
            for (CourseOutlineItemView item : unit.items()) {
                if (item.itemType() == CourseOutlineItemType.MATERIAL) {
                    continue;
                }
                boolean itemDone = completion.test(item);
                String key = contentKey(item);
                all.add(key);
                if (itemDone) {
                    done.add(key);
                }
                items.add(new ItemProgress(item.id(), item.itemType(), item.contentId(),
                        item.title(), itemDone));
            }
            out.add(new UnitProgress(unit.id(), unit.title(), depth, items));
            visit(unit.children(), depth + 1, out, all, done, completion);
        }
    }
}
