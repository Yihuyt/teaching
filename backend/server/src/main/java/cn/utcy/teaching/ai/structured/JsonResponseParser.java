package cn.utcy.teaching.ai.structured;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.haibiiin.json.repair.JSONRepair;
import io.github.haibiiin.json.repair.JSONRepairConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 回复的三级 JSON 解析:
 *   1. 剥 markdown 代码块后直接解析(先于 &lt;think&gt; 清理,合法 JSON 里的该字样不被误伤)
 *   2. 清理思维链 &lt;think&gt; 前缀后重试
 *   3. 扫描全文取「最长可解码的顶层 JSON 值」(载荷两侧的散文里可能混有小片段合法 JSON,
 *      取最长者即真实载荷)
 *   4. json-repair 库自动修复残缺 JSON
 * 全部失败返回 null,由调用方决定降级行为。
 */
public final class JsonResponseParser {

    private static final Logger log = LoggerFactory.getLogger(JsonResponseParser.class);

    /** FAIL_ON_TRAILING_TOKENS:值后还有内容即失败,散文尾巴不被静默吞掉 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 宽容读(尾逗号/单引号/未加引号字段名等常见 LLM 毛病),仍拒绝散文尾巴 */
    private static final ObjectMapper LENIENT_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** 扫描层专用:只读取首个完整值,值后允许还有内容 */
    private static final ObjectMapper SCAN_MAPPER = new ObjectMapper();

    private static final Pattern CODE_FENCE =
            Pattern.compile("```(?:json)?\\s*\\n?(.*?)```", Pattern.DOTALL);
    private static final Pattern THINK_BLOCK = Pattern.compile(
            "<think\\b[^>]*>.*?</think>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern THINK_PREFIX = Pattern.compile(
            "^\\s*<think\\b[^>]*>.*?(?=[{\\[])", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private JsonResponseParser() {
    }

    public static JsonNode parse(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        String extracted = response;
        if (response.contains("```")) {
            Matcher fence = CODE_FENCE.matcher(response);
            if (fence.find()) {
                extracted = fence.group(1).strip();
            }
        }

        JsonNode direct = tryParse(extracted);
        if (direct != null) {
            return direct;
        }

        if (extracted.toLowerCase().contains("<think")) {
            String cleaned = THINK_PREFIX.matcher(
                    THINK_BLOCK.matcher(extracted).replaceAll("")).replaceFirst("").strip();
            if (!cleaned.equals(extracted.strip())) {
                if (cleaned.isEmpty()) {
                    return null;
                }
                JsonNode afterThink = tryParse(cleaned);
                if (afterThink != null) {
                    return afterThink;
                }
                extracted = cleaned;
            }
        }

        // 先取最长可解码值再修复:载荷周围带括号的散文若直接交给修复库,
        // 会被连同载荷一起包成数组,反而毁掉本来完整的载荷
        JsonNode decoded = decodeLongestJsonValue(extracted);
        if (decoded != null) {
            return decoded;
        }

        try {
            String repaired = new JSONRepair(new JSONRepairConfig().enableExtractJSON())
                    .handle(extracted);
            return usable(LENIENT_MAPPER.readTree(repaired));
        } catch (Exception exception) {
            log.debug("JSON 修复失败: {}", exception.getMessage());
            return null;
        }
    }

    private static JsonNode decodeLongestJsonValue(String text) {
        JsonNode best = null;
        long bestLength = 0;
        int pos = 0;
        while (pos < text.length()) {
            int objectStart = text.indexOf('{', pos);
            int arrayStart = text.indexOf('[', pos);
            int start = objectStart == -1 ? arrayStart
                    : arrayStart == -1 ? objectStart : Math.min(objectStart, arrayStart);
            if (start == -1) {
                return best;
            }
            try (var parser = SCAN_MAPPER.getFactory().createParser(text.substring(start))) {
                JsonNode parsed = SCAN_MAPPER.readTree(parser);
                long consumed = parser.currentLocation().getCharOffset();
                if (parsed != null && consumed > bestLength) {
                    best = parsed;
                    bestLength = consumed;
                }
                pos = start + Math.max(1, (int) consumed);
            } catch (JsonProcessingException exception) {
                // 越过失败点继续:失败区间内部的括号只能解出它的碎片,交给修复层处理
                long failedAt = exception.getLocation() == null
                        ? 1 : exception.getLocation().getCharOffset();
                pos = start + (int) Math.max(1, failedAt);
            } catch (Exception exception) {
                return best;
            }
        }
        return best;
    }

    private static JsonNode tryParse(String text) {
        try {
            return usable(MAPPER.readTree(text));
        } catch (Exception strictFailure) {
            try {
                return usable(LENIENT_MAPPER.readTree(text));
            } catch (Exception lenientFailure) {
                return null;
            }
        }
    }

    private static JsonNode usable(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node;
    }
}
