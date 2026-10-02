package cn.utcy.teaching.knowledgegraph.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无目录页教材的回退识别(纯函数,确定性):从分页正文中按编号模式提取标题行,
 * level 由编号规则直接推导(第X章→1、X.Y→2、X.Y.Z→3、附录/项目→1),
 * 不猜层级、不用 LLM。识别不出足够条目时由调用方标记 degraded 交教师人工核对。
 */
public final class TocCandidateExtractor {

    private static final Pattern CHAPTER = Pattern.compile(
            "^第\\s*([一二三四五六七八九十百千万零〇\\d]+)\\s*章\\s*(\\S.*)$");
    private static final Pattern PART = Pattern.compile(
            "^第\\s*([一二三四五六七八九十百千万零〇\\d]+)\\s*部分\\s*(\\S.*)$");
    private static final Pattern NUMERIC = Pattern.compile(
            "^(\\d+(?:\\.\\d+){0,3})[\\s、.．]\\s*(\\S.*)$");
    private static final Pattern APPENDIX = Pattern.compile(
            "^附录\\s*([A-Za-z一二三四五六七八九十]+)\\s*(\\S.*)$");
    private static final Pattern PROJECT = Pattern.compile("^项目\\s*(\\d+)\\s*(\\S.*)$");
    private static final int MAX_TITLE_LINE_CHARS = 80;

    private TocCandidateExtractor() {
    }

    /** @param pages 分页正文(页码 = 下标 + 1) */
    public static List<TocEntry> extract(List<String> pages) {
        List<TocEntry> entries = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            int page = pageIndex + 1;
            for (String line : pages.get(pageIndex).split("\n")) {
                String text = line.strip();
                if (text.isEmpty() || text.length() > MAX_TITLE_LINE_CHARS) {
                    continue;
                }
                TocEntry entry = match(text, page);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private static TocEntry match(String text, int page) {
        Matcher chapter = CHAPTER.matcher(text);
        if (chapter.matches()) {
            return new TocEntry("第" + chapter.group(1) + "章", chapter.group(2).strip(), 1, page, 0);
        }
        Matcher part = PART.matcher(text);
        if (part.matches()) {
            return new TocEntry("第" + part.group(1) + "部分", part.group(2).strip(), 1, page, 0);
        }
        Matcher appendix = APPENDIX.matcher(text);
        if (appendix.matches()) {
            return new TocEntry("附录" + appendix.group(1), appendix.group(2).strip(), 1, page, 0);
        }
        Matcher project = PROJECT.matcher(text);
        if (project.matches()) {
            return new TocEntry("项目" + project.group(1), project.group(2).strip(), 1, page, 0);
        }
        Matcher numeric = NUMERIC.matcher(text);
        if (numeric.matches()) {
            String number = numeric.group(1);
            int level = Math.min(6, number.split("\\.").length);
            String title = numeric.group(2).strip();
            // 纯数字开头的普通句子(如 "3 个要点")不是标题:标题不以标点结尾且不含句号
            if (title.contains("。")) {
                return null;
            }
            return new TocEntry(number, title, level, page, 0);
        }
        return null;
    }
}
