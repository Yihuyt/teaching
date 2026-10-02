package cn.utcy.teaching.ai.document;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * MinerU layout.json → 分页纯文本(页码 = 列表下标 + 1):
 * 叶块按 index 排序拼接 span 文本;discarded_blocks 中的页眉/页脚/页码文本
 * 若在 ≥3 页的页面边缘正文块中重复出现,视为边缘噪声一并剔除。
 * 用 Jackson 流式解析逐页处理(两遍:先收集边缘噪声,再取正文),内存峰值只有一页的树,
 * 而不是整段几百页的 JSON 树。
 */
public final class MineruLayoutPages {

    private static final Set<String> EDGE_DISCARDED_TYPES = Set.of("header", "footer", "page_number");
    private static final int EDGE_NOISE_MIN_REPEAT_PAGES = 3;
    private static final int EDGE_NOISE_MIN_TEXT_LEN = 4;
    private static final double EDGE_TOP_RATIO = 0.12;
    private static final double EDGE_BOTTOM_RATIO = 0.88;

    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern PUNCTUATION = Pattern.compile(
            "[，。:：;；、()（）\\[\\]【】《》“”\"'`·…\\.\\*•—–_-]+");
    private static final Pattern DIGITS_ONLY = Pattern.compile("[\\d\\.-]+");

    private MineruLayoutPages() {
    }

    public static List<String> fromLayoutJson(byte[] layoutJson, ObjectMapper mapper) {
        Set<String> discardedTexts = new HashSet<>();
        Map<String, Set<Integer>> edgeTextPages = new HashMap<>();
        int[] pageCount = {0};
        forEachPage(layoutJson, mapper, page -> {
            pageCount[0]++;
            collectDiscardedTexts(page, discardedTexts);
        });
        if (pageCount[0] == 0) {
            throw new MineruClient.MineruUnavailableException("MinerU layout.json 中找不到分页信息(pdf_info)");
        }
        if (!discardedTexts.isEmpty()) {
            forEachPage(layoutJson, mapper, page -> collectEdgeRepeats(page, discardedTexts, edgeTextPages));
        }
        Set<String> edgeNoise = new HashSet<>();
        edgeTextPages.forEach((text, pages) -> {
            if (pages.size() >= EDGE_NOISE_MIN_REPEAT_PAGES) {
                edgeNoise.add(text);
            }
        });

        TreeMap<Integer, String> byIndex = new TreeMap<>();
        forEachPage(layoutJson, mapper, page -> {
            int pageIdx = page.path("page_idx").asInt(-1);
            if (pageIdx >= 0) {
                byIndex.put(pageIdx, extractPageText(page, edgeNoise));
            }
        });
        int maxPageIdx = byIndex.isEmpty() ? -1 : byIndex.lastKey();
        List<String> result = new ArrayList<>(maxPageIdx + 1);
        for (int i = 0; i <= maxPageIdx; i++) {
            result.add(byIndex.getOrDefault(i, ""));
        }
        return result;
    }

    /** 流式遍历 pdf_info 里的每一页(数组直挂或嵌在 pages 下),一次只物化一页 */
    private static void forEachPage(byte[] layoutJson, ObjectMapper mapper, Consumer<JsonNode> visitor) {
        // 逐页从流中间读树:读完一页后面还有下一页,平台 mapper 的"值后不许有多余 token"检查在这里必须关掉
        ObjectReader pageReader = mapper.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try (JsonParser parser = mapper.getFactory().createParser(layoutJson)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new MineruClient.MineruUnavailableException("MinerU layout.json 不是 JSON 对象");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (!"pdf_info".equals(field)) {
                    parser.skipChildren();
                    continue;
                }
                if (value == JsonToken.START_ARRAY) {
                    visitArray(parser, pageReader, visitor);
                } else if (value == JsonToken.START_OBJECT) {
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        String inner = parser.currentName();
                        JsonToken innerValue = parser.nextToken();
                        if ("pages".equals(inner) && innerValue == JsonToken.START_ARRAY) {
                            visitArray(parser, pageReader, visitor);
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else {
                    parser.skipChildren();
                }
                return;
            }
        } catch (IOException exception) {
            throw new MineruClient.MineruUnavailableException("MinerU layout.json 不是合法 JSON", exception);
        }
    }

    private static void visitArray(JsonParser parser, ObjectReader pageReader, Consumer<JsonNode> visitor)
            throws IOException {
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            visitor.accept(pageReader.readTree(parser));
        }
    }

    private static void collectDiscardedTexts(JsonNode page, Set<String> discardedTexts) {
        for (JsonNode block : leafBlocks(page.path("discarded_blocks"))) {
            String type = block.path("type").asText("").trim().toLowerCase();
            if (!EDGE_DISCARDED_TYPES.contains(type)) {
                continue;
            }
            String normalized = normalizeEdgeText(blockText(block));
            if (isValidEdgeText(normalized)) {
                discardedTexts.add(normalized);
            }
        }
    }

    private static void collectEdgeRepeats(JsonNode page, Set<String> discardedTexts,
                                           Map<String, Set<Integer>> edgeTextPages) {
        int pageIdx = page.path("page_idx").asInt(-1);
        double pageHeight = pageHeight(page);
        if (pageIdx < 0 || pageHeight <= 0) {
            return;
        }
        for (JsonNode block : leafBlocks(page.path("para_blocks"))) {
            if (!isNearPageEdge(block, pageHeight)) {
                continue;
            }
            String normalized = normalizeEdgeText(blockText(block));
            if (isValidEdgeText(normalized) && discardedTexts.contains(normalized)) {
                edgeTextPages.computeIfAbsent(normalized, key -> new HashSet<>()).add(pageIdx);
            }
        }
    }

    private static String extractPageText(JsonNode page, Set<String> edgeNoise) {
        StringBuilder out = new StringBuilder();
        double pageHeight = pageHeight(page);
        for (JsonNode block : leafBlocks(page.path("para_blocks"))) {
            String text = blockText(block);
            if (text.isEmpty()) {
                continue;
            }
            if (!edgeNoise.isEmpty() && pageHeight > 0) {
                String normalized = normalizeEdgeText(text);
                if (isValidEdgeText(normalized) && edgeNoise.contains(normalized)
                        && isNearPageEdge(block, pageHeight)) {
                    continue;
                }
            }
            out.append(text).append("\n\n");
        }
        return out.toString().strip();
    }

    /** 叶块遍历:有 index 的按 index 升序,无 index 的保持原序排在其后 */
    private static List<JsonNode> leafBlocks(JsonNode blocks) {
        if (!blocks.isArray()) {
            return List.of();
        }
        record Ordered(int hasIndex, int index, int seq, JsonNode block) {
        }
        List<Ordered> ordered = new ArrayList<>();
        int seq = 0;
        for (JsonNode block : blocks) {
            JsonNode index = block.path("index");
            ordered.add(index.isInt()
                    ? new Ordered(0, index.asInt(), seq, block)
                    : new Ordered(1, seq, seq, block));
            seq++;
        }
        ordered.sort(Comparator
                .comparingInt(Ordered::hasIndex)
                .thenComparingInt(Ordered::index)
                .thenComparingInt(Ordered::seq));
        List<JsonNode> leaves = new ArrayList<>();
        for (Ordered item : ordered) {
            JsonNode sub = item.block().path("blocks");
            if (sub.isArray() && !sub.isEmpty()) {
                if (item.block().path("lines").isArray() && !item.block().path("lines").isEmpty()) {
                    leaves.add(item.block());
                }
                leaves.addAll(leafBlocks(sub));
            } else {
                leaves.add(item.block());
            }
        }
        return leaves;
    }

    private static String blockText(JsonNode block) {
        StringBuilder lines = new StringBuilder();
        for (JsonNode line : block.path("lines")) {
            StringBuilder parts = new StringBuilder();
            for (JsonNode span : line.path("spans")) {
                JsonNode content = span.path("content");
                if (!content.isTextual()) {
                    content = span.path("text");
                }
                if (content.isTextual()) {
                    parts.append(content.asText());
                } else if (span.path("html").isTextual()) {
                    // 表格正文在 html 字段(table_body 的 span 没有 content):转纯文本,否则表格内容整体丢失
                    parts.append(tableHtmlToText(span.path("html").asText()));
                }
            }
            String lineText = parts.toString().strip();
            if (!lineText.isEmpty()) {
                if (!lines.isEmpty()) {
                    lines.append('\n');
                }
                lines.append(lineText);
            }
        }
        if (lines.isEmpty() && block.path("text").isTextual()) {
            return block.path("text").asText().strip();
        }
        return lines.toString();
    }

    static String tableHtmlToText(String html) {
        String text = html
                .replaceAll("(?i)</tr\\s*>", "\n")
                .replaceAll("(?i)</t[dh]\\s*>", " | ")
                .replaceAll("<[^>]+>", "")
                .replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
                .replace("&nbsp;", " ").replace("&quot;", "\"");
        StringBuilder out = new StringBuilder();
        for (String row : text.split("\n")) {
            String trimmed = row.strip().replaceAll("\\s*\\|\\s*$", "");
            if (!trimmed.isEmpty()) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(trimmed);
            }
        }
        return out.toString();
    }

    private static double pageHeight(JsonNode page) {
        JsonNode size = page.path("page_size");
        return size.isArray() && size.size() >= 2 ? size.get(1).asDouble(0) : 0;
    }

    private static boolean isNearPageEdge(JsonNode block, double pageHeight) {
        JsonNode bbox = block.path("bbox");
        if (!bbox.isArray() || bbox.size() < 4) {
            return false;
        }
        double y0 = bbox.get(1).asDouble(Double.NaN);
        double y1 = bbox.get(3).asDouble(Double.NaN);
        if (Double.isNaN(y0) || Double.isNaN(y1)) {
            return false;
        }
        return y1 <= pageHeight * EDGE_TOP_RATIO || y0 >= pageHeight * EDGE_BOTTOM_RATIO;
    }

    private static String normalizeEdgeText(String text) {
        String compact = SPACES.matcher(text.strip().toLowerCase()).replaceAll("");
        return PUNCTUATION.matcher(compact).replaceAll("");
    }

    private static boolean isValidEdgeText(String normalized) {
        return normalized.length() >= EDGE_NOISE_MIN_TEXT_LEN
                && !DIGITS_ONLY.matcher(normalized).matches();
    }
}
