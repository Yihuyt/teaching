package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.util.BoundedParallel;
import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.ai.structured.StructuredGenerator;
import cn.utcy.teaching.knowledgegraph.domain.TocCandidateExtractor;
import cn.utcy.teaching.knowledgegraph.domain.TocEntry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 教材目录识别(目录页优先 + PageIndex 的验证修复):
 * 1. 前 20 页逐页 LLM 判定目录页(摘要/图表清单不算);
 * 2. 目录页文本 → 确定性解析(number 推 level、页码禁猜、续行拼接);
 * 3. 页码对齐:标题在分页正文中归一化匹配 →(印刷页,物理页)对 → 众数 offset 校准,
 *    缺页条目在最近已知邻居页窗内搜索填充;
 * 4. 逐条验证标题出现在声称页(±1 容差),错误项邻居窗内修复;
 * 5. 准确率 <0.6 或无目录页 → 回退:编号正则确定性提取(TocCandidateExtractor),
 *    仍不足 → degraded=true,交教师人工核对(教师修正路径是唯一兜底,代码层不吞错)。
 */
@Service
public class TocRecognitionService {

    private static final int TOC_SCAN_PAGES = 20;
    private static final int JUDGE_PARALLELISM = 4;
    private static final int TOC_PAGE_TEXT_LIMIT = 3000;
    private static final double MIN_ACCURACY = 0.6;
    private static final int MIN_ENTRIES = 3;

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern PUNCTUATION = Pattern.compile(
            "[，。:：;；、()（）\\[\\]【】《》\"'`·…\\.\\*•—–_-]+");
    private static final Pattern TRAILING_PAGE = Pattern.compile("\\s*\\d+\\s*$");

    public record TocDraft(boolean degraded, List<Integer> tocPages, List<TocEntry> entries,
                           List<String> notes) {
    }

    private final StructuredGenerator structured;
    private final SchemaRegistry schemas;
    private final PromptLoader prompts;
    private final ModelConfig llmModel;
    private final TaskExecutor executor;

    public TocRecognitionService(StructuredGenerator structured,
                                 @Qualifier("knowledgegraphSchemas") SchemaRegistry schemas,
                                 PromptLoader prompts,
                                 @Qualifier("knowledgegraphLlmModel") ModelConfig llmModel,
                                 @Qualifier("kgTaskExecutor") TaskExecutor executor) {
        this.structured = structured;
        this.schemas = schemas;
        this.prompts = prompts;
        this.llmModel = llmModel;
        this.executor = executor;
    }

    public TocDraft recognize(String apiKey, List<String> pages, Consumer<String> onProgress) {
        List<Integer> tocPages = detectTocPages(apiKey, pages, onProgress);
        List<String> notes = new ArrayList<>();
        if (!tocPages.isEmpty()) {
            onProgress.accept("发现目录页 " + tocPages + ",正在解析目录…");
            List<ParsedEntry> parsed = parseTocPages(apiKey, pages, tocPages);
            List<TocEntry> aligned = alignPages(parsed, pages, tocPages, notes);
            double accuracy = verifyAndFix(aligned, pages, notes);
            if (accuracy >= MIN_ACCURACY && aligned.size() >= MIN_ENTRIES) {
                return new TocDraft(false, tocPages, withEndPages(aligned, pages.size()), notes);
            }
            notes.add("目录页解析准确率 " + Math.round(accuracy * 100) + "%,已回退为正文编号识别");
        }

        onProgress.accept("按正文编号模式识别目录…");
        List<TocEntry> fallback = TocCandidateExtractor.extract(
                tocPages.isEmpty() ? pages : withoutPages(pages, tocPages));
        boolean degraded = fallback.size() < MIN_ENTRIES;
        if (degraded) {
            notes.add("自动识别未达质量要求,请人工核对并修正目录");
        }
        return new TocDraft(degraded, tocPages, withEndPages(fallback, pages.size()), notes);
    }

    /** 止页初值:下一条(任意层级)已知页码(边界页共享),末条止于全书末页;页码未知(0)的条目不推 */
    private static List<TocEntry> withEndPages(List<TocEntry> entries, int pageCount) {
        List<TocEntry> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            TocEntry entry = entries.get(i);
            if (entry.page() <= 0) {
                result.add(entry);
                continue;
            }
            int end = pageCount;
            for (int next = i + 1; next < entries.size(); next++) {
                if (entries.get(next).page() > 0) {
                    end = Math.max(entry.page(), entries.get(next).page());
                    break;
                }
            }
            result.add(new TocEntry(entry.number(), entry.title(), entry.level(), entry.page(), end));
        }
        return result;
    }

    // ---- 目录页探测 ------------------------------------------------------------

    private List<Integer> detectTocPages(String apiKey, List<String> pages,
                                         Consumer<String> onProgress) {
        int limit = Math.min(TOC_SCAN_PAGES, pages.size());
        onProgress.accept("正在并行检查前 " + limit + " 页是否为目录页…");
        // 每页判定互相独立,并行判;空白页不判。目录段之后的页也会被判(每次几千字符的小调用)
        List<Supplier<Boolean>> tasks = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            String text = pages.get(i);
            tasks.add(text.isBlank() ? () -> false : () -> judgeTocPage(apiKey, Text.truncate(text, TOC_PAGE_TEXT_LIMIT)));
        }
        return firstContiguousRun(BoundedParallel.run(executor, JUDGE_PARALLELISM, tasks, () -> false));
    }

    /** 目录页假定连续:取首个连续 true 段的页码(1 起);无 true 返回空 */
    static List<Integer> firstContiguousRun(List<Boolean> verdicts) {
        List<Integer> run = new ArrayList<>();
        for (int i = 0; i < verdicts.size(); i++) {
            if (verdicts.get(i)) {
                run.add(i + 1);
            } else if (!run.isEmpty()) {
                break;
            }
        }
        return run;
    }

    private boolean judgeTocPage(String apiKey, String pageText) {
        PromptLoader.Prompt prompt = prompts.build("knowledgegraph/prompts", "toc-detect",
                Map.of("pageText", pageText,
                        "schemaJson", schemas.rawSchema("toc-detect")));
        StructuredGenerator.Result<Boolean> result = structured.generate(
                new StructuredGenerator.Request<>(
                        apiKey, llmModel, "toc-detect",
                        schemas.rawSchema("toc-detect"), schemas.validator("toc-detect"),
                        prompt.system(), prompt.user(), 2,
                        parsed -> StructuredGenerator.Refined.value(
                                parsed.path("isToc").asBoolean(false)),
                        null));
        return result.value();
    }

    // ---- 目录页确定性解析 ------------------------------------------------------

    private record ParsedEntry(String number, String title, int level, int pagePrint) {
    }

    private List<ParsedEntry> parseTocPages(String apiKey, List<String> pages,
                                            List<Integer> tocPages) {
        StringBuilder tocText = new StringBuilder();
        for (int page : tocPages) {
            tocText.append(pages.get(page - 1)).append('\n');
        }
        PromptLoader.Prompt prompt = prompts.build("knowledgegraph/prompts", "toc-parse",
                Map.of("tocText", tocText.toString(),
                        "schemaJson", schemas.rawSchema("toc-parse")));
        StructuredGenerator.Result<List<ParsedEntry>> result = structured.generate(
                new StructuredGenerator.Request<>(
                        apiKey, llmModel, "toc-parse",
                        schemas.rawSchema("toc-parse"), schemas.validator("toc-parse"),
                        prompt.system(), prompt.user(), 3,
                        this::refineParsedToc,
                        null));
        return result.value();
    }

    private StructuredGenerator.Refined<List<ParsedEntry>> refineParsedToc(JsonNode parsed) {
        List<ParsedEntry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int previousPage = 0;
        int index = 0;
        for (JsonNode item : parsed.path("entries")) {
            index++;
            String title = item.path("title").asText("").strip();
            int level = item.path("level").asInt(0);
            int pagePrint = item.path("pagePrint").asInt(0);
            if (title.isEmpty()) {
                errors.add("entries[" + index + "] 标题为空");
                continue;
            }
            if (level < 1 || level > 6) {
                errors.add("entries[" + index + "]「" + title + "」level=" + level + " 非法");
                continue;
            }
            if (pagePrint > 0) {
                if (pagePrint < previousPage) {
                    errors.add("entries[" + index + "]「" + title + "」页码 " + pagePrint
                            + " 递减(前一条 " + previousPage + "),请按目录阅读顺序修正");
                }
                previousPage = pagePrint;
            }
            entries.add(new ParsedEntry(item.path("number").asText("").strip(),
                    title, level, pagePrint));
        }
        if (entries.size() < MIN_ENTRIES) {
            errors.add("解析出的目录条目不足 " + MIN_ENTRIES + " 条");
        }
        return errors.isEmpty()
                ? StructuredGenerator.Refined.value(entries)
                : StructuredGenerator.Refined.errors(errors);
    }

    // ---- 页码对齐(印刷页 → 物理页,众数 offset) --------------------------------

    private List<TocEntry> alignPages(List<ParsedEntry> parsed, List<String> pages,
                                      List<Integer> tocPages, List<String> notes) {
        int searchFrom = tocPages.get(tocPages.size() - 1) + 1;
        Map<Integer, Integer> offsetVotes = new HashMap<>();
        Map<Integer, Integer> matchedPhysical = new LinkedHashMap<>();
        for (int i = 0; i < parsed.size(); i++) {
            ParsedEntry entry = parsed.get(i);
            int physical = findTitlePage(entry, pages, searchFrom, pages.size());
            if (physical > 0) {
                matchedPhysical.put(i, physical);
                if (entry.pagePrint() > 0) {
                    offsetVotes.merge(physical - entry.pagePrint(), 1, Integer::sum);
                }
            }
        }
        int offset = offsetVotes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);
        if (!offsetVotes.isEmpty()) {
            notes.add("印刷页与 PDF 页偏移 " + offset + "(依据 "
                    + offsetVotes.values().stream().mapToInt(Integer::intValue).sum() + " 处标题匹配)");
        }

        List<TocEntry> aligned = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            ParsedEntry entry = parsed.get(i);
            int page;
            if (entry.pagePrint() > 0 && !offsetVotes.isEmpty()) {
                page = entry.pagePrint() + offset;
            } else if (matchedPhysical.containsKey(i)) {
                page = matchedPhysical.get(i);
            } else {
                page = 0; // 留给邻居窗修复;仍失败则由教师在确认页补
            }
            aligned.add(new TocEntry(entry.number(), entry.title(), entry.level(),
                    clamp(page, pages.size()), 0));
        }
        fillMissingByNeighbors(aligned, parsed, pages, searchFrom);
        return aligned;
    }

    private void fillMissingByNeighbors(List<TocEntry> aligned, List<ParsedEntry> parsed,
                                        List<String> pages, int searchFrom) {
        for (int i = 0; i < aligned.size(); i++) {
            if (aligned.get(i).page() > 0) {
                continue;
            }
            int windowStart = searchFrom;
            for (int back = i - 1; back >= 0; back--) {
                if (aligned.get(back).page() > 0) {
                    windowStart = aligned.get(back).page();
                    break;
                }
            }
            int windowEnd = pages.size();
            for (int forward = i + 1; forward < aligned.size(); forward++) {
                if (aligned.get(forward).page() > 0) {
                    windowEnd = aligned.get(forward).page();
                    break;
                }
            }
            int found = findTitlePage(parsed.get(i), pages, windowStart, windowEnd);
            if (found > 0) {
                aligned.set(i, new TocEntry(aligned.get(i).number(), aligned.get(i).title(),
                        aligned.get(i).level(), found, 0));
            }
        }
    }

    // ---- 验证与修复 ------------------------------------------------------------

    private double verifyAndFix(List<TocEntry> entries, List<String> pages, List<String> notes) {
        int verified = 0;
        int fixed = 0;
        for (int i = 0; i < entries.size(); i++) {
            TocEntry entry = entries.get(i);
            if (entry.page() > 0 && titleOnPage(entry, pages, entry.page())) {
                verified++;
                continue;
            }
            int windowStart = i > 0 ? Math.max(1, entries.get(i - 1).page()) : 1;
            int windowEnd = i + 1 < entries.size() && entries.get(i + 1).page() > 0
                    ? entries.get(i + 1).page() : pages.size();
            int found = findTitlePage(
                    new ParsedEntry(entry.number(), entry.title(), entry.level(), 0),
                    pages, windowStart, windowEnd);
            if (found > 0) {
                entries.set(i, new TocEntry(entry.number(), entry.title(), entry.level(), found, 0));
                verified++;
                fixed++;
            }
        }
        if (fixed > 0) {
            notes.add("已按正文位置修正 " + fixed + " 条目录页码");
        }
        return entries.isEmpty() ? 0 : (double) verified / entries.size();
    }

    private boolean titleOnPage(TocEntry entry, List<String> pages, int page) {
        for (int p = Math.max(1, page - 1); p <= Math.min(pages.size(), page + 1); p++) {
            if (pageContainsTitle(pages.get(p - 1), entry.number(), entry.title())) {
                return true;
            }
        }
        return false;
    }

    private int findTitlePage(ParsedEntry entry, List<String> pages, int fromPage, int toPage) {
        for (int page = Math.max(1, fromPage); page <= Math.min(pages.size(), toPage); page++) {
            if (pageContainsTitle(pages.get(page - 1), entry.number(), entry.title())) {
                return page;
            }
        }
        return 0;
    }

    private boolean pageContainsTitle(String pageText, String number, String title) {
        String normalizedTitle = normalize(title);
        if (normalizedTitle.length() < 2) {
            return false;
        }
        String normalizedNumbered = normalize((number == null ? "" : number) + title);
        for (String line : pageText.split("\n")) {
            String normalizedLine = normalize(TRAILING_PAGE.matcher(line).replaceAll(""));
            if (normalizedLine.isEmpty() || normalizedLine.length() > 120) {
                continue;
            }
            if (normalizedLine.contains(normalizedNumbered)
                    || normalizedLine.contains(normalizedTitle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        String compact = SPACES.matcher(text.strip().toLowerCase()).replaceAll("");
        return PUNCTUATION.matcher(compact).replaceAll("");
    }

    private static List<String> withoutPages(List<String> pages, List<Integer> excluded) {
        List<String> result = new ArrayList<>(pages);
        for (int page : excluded) {
            result.set(page - 1, "");
        }
        return result;
    }

    private static int clamp(int page, int pageCount) {
        return page < 0 ? 0 : Math.min(page, pageCount);
    }

}
