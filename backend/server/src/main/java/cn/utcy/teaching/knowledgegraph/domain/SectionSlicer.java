package cn.utcy.teaching.knowledgegraph.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 按确认稿切分抽取单元(纯函数)。
 * 页区间取自条目自身的 [page, endPage](闭区间,边界页与邻居共享);不落在任何叶子
 * 区间内的页不参与抽取。超长节(正文超过 maxChars)按段落边界均分为多个抽取子片,标题带 (i/n) 后缀。
 */
public final class SectionSlicer {

    public record Slice(int entryIndex, String number, String title, String path,
                        int startPage, int endPage, String text) {
    }

    private SectionSlicer() {
    }

    public static List<Slice> slice(List<TocEntry> entries, List<String> pages, int maxChars) {
        List<Integer> leaves = TocOutline.leafIndexes(entries);
        List<Slice> slices = new ArrayList<>();
        for (int leaf : leaves) {
            TocEntry entry = entries.get(leaf);
            int startPage = entry.page();
            int endPage = Math.max(startPage, entry.endPage());
            String text = joinPages(pages, startPage, endPage);
            String path = TocOutline.path(entries, leaf);
            if (text.length() <= maxChars) {
                slices.add(new Slice(leaf, nz(entry.number()), entry.title().trim(), path,
                        startPage, endPage, text));
                continue;
            }
            List<String> parts = splitByParagraph(text, maxChars);
            for (int i = 0; i < parts.size(); i++) {
                String suffix = " (" + (i + 1) + "/" + parts.size() + ")";
                slices.add(new Slice(leaf, nz(entry.number()),
                        entry.title().trim() + suffix, path + suffix,
                        startPage, endPage, parts.get(i)));
            }
        }
        return slices;
    }

    private static String joinPages(List<String> pages, int startPage, int endPage) {
        StringBuilder out = new StringBuilder();
        for (int page = startPage; page <= endPage && page <= pages.size(); page++) {
            String text = pages.get(page - 1);
            if (!text.isBlank()) {
                if (!out.isEmpty()) {
                    out.append("\n\n");
                }
                out.append(text.strip());
            }
        }
        return out.toString();
    }

    private static List<String> splitByParagraph(String text, int maxChars) {
        int partCount = (text.length() + maxChars - 1) / maxChars;
        int target = (text.length() + partCount - 1) / partCount;
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\n\n", -1)) {
            while (paragraph.length() > maxChars) {
                flush(parts, current);
                parts.add(paragraph.substring(0, maxChars));
                paragraph = paragraph.substring(maxChars);
            }
            if (!current.isEmpty() && current.length() + paragraph.length() + 2 > target) {
                flush(parts, current);
            }
            if (!paragraph.isBlank()) {
                if (!current.isEmpty()) {
                    current.append("\n\n");
                }
                current.append(paragraph);
            }
        }
        flush(parts, current);
        return parts;
    }

    private static void flush(List<String> parts, StringBuilder current) {
        if (!current.isEmpty()) {
            parts.add(current.toString());
            current.setLength(0);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value.trim();
    }
}
