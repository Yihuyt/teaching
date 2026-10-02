package cn.utcy.teaching.knowledgegraph.domain;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.shared.error.BadRequestException;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录确认稿的结构校验与叶子枚举(纯函数)。
 * 确认稿契约:非空;标题非空 ≤255;1≤level≤6 且相对前一条最多加深一级(首条必须 level 1);
 * 页码 ∈ [1, pageCount] 且整体非递减;止页 ∈ [起页, pageCount];叶子条目的止页不越过下一叶子的起页(边界页共享,不重复抽取)。
 * 不落在任何条目区间内的页不参与抽取(删条目 / 留缝隙 = 有意剔除前言、习题答案、后续章节等)。
 */
public final class TocOutline {

    private TocOutline() {
    }

    public static void validate(List<TocEntry> entries, int pageCount) {
        if (entries == null || entries.isEmpty()) {
            throw new BadRequestException("目录不能为空");
        }
        int previousLevel = 0;
        int previousPage = 0;
        for (int i = 0; i < entries.size(); i++) {
            TocEntry entry = entries.get(i);
            String where = "第 " + (i + 1) + " 条「" + safeTitle(entry) + "」";
            if (entry.title() == null || entry.title().isBlank()) {
                throw new BadRequestException("目录第 " + (i + 1) + " 条标题不能为空");
            }
            if (entry.title().length() > 255) {
                throw new BadRequestException(where + "标题不能超过 255 个字符");
            }
            if (entry.number() != null && entry.number().length() > 64) {
                throw new BadRequestException(where + "编号不能超过 64 个字符");
            }
            if (entry.level() < 1 || entry.level() > 6) {
                throw new BadRequestException(where + "层级必须在 1~6 之间");
            }
            if (entry.level() > previousLevel + 1) {
                throw new BadRequestException(where + "层级跳变:上一条是 "
                        + previousLevel + " 级,本条不能直接是 " + entry.level() + " 级");
            }
            if (entry.page() < 1 || entry.page() > pageCount) {
                throw new BadRequestException(where + "页码必须在 1~" + pageCount + " 之间");
            }
            if (entry.page() < previousPage) {
                throw new BadRequestException(where + "页码 " + entry.page()
                        + " 小于前一条的 " + previousPage + ",目录页码必须非递减");
            }
            if (entry.endPage() < entry.page() || entry.endPage() > pageCount) {
                throw new BadRequestException(where + "止页必须在起页 " + entry.page()
                        + " 与全书末页 " + pageCount + " 之间");
            }
            previousLevel = entry.level();
            previousPage = entry.page();
        }
        List<Integer> leaves = leafIndexes(entries);
        for (int i = 0; i + 1 < leaves.size(); i++) {
            TocEntry leaf = entries.get(leaves.get(i));
            TocEntry next = entries.get(leaves.get(i + 1));
            if (leaf.endPage() > next.page()) {
                throw new BadRequestException("第 " + (leaves.get(i) + 1) + " 条「" + safeTitle(leaf) + "」止页 "
                        + leaf.endPage() + " 越过了下一小节「" + safeTitle(next) + "」的起页 " + next.page()
                        + ",两节正文会被重复抽取");
            }
        }
    }

    public static List<Integer> leafIndexes(List<TocEntry> entries) {
        List<Integer> leaves = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            boolean hasChild = i + 1 < entries.size()
                    && entries.get(i + 1).level() > entries.get(i).level();
            if (!hasChild) {
                leaves.add(i);
            }
        }
        return leaves;
    }

    public static String path(List<TocEntry> entries, int index) {
        List<String> parts = new ArrayList<>();
        int level = entries.get(index).level();
        parts.add(display(entries.get(index)));
        for (int i = index - 1; i >= 0 && level > 1; i--) {
            if (entries.get(i).level() < level) {
                level = entries.get(i).level();
                parts.add(0, display(entries.get(i)));
            }
        }
        return String.join(" > ", parts);
    }

    public static String display(TocEntry entry) {
        String number = entry.number() == null ? "" : entry.number().trim();
        return number.isEmpty() ? entry.title().trim() : number + " " + entry.title().trim();
    }

    private static String safeTitle(TocEntry entry) {
        String title = entry.title() == null ? "" : entry.title().trim();
        return Text.abbreviate(title, 30);
    }
}
