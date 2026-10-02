package cn.utcy.teaching.retrieval.application;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.KnowledgeBaseRef;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.RetrievedPassage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程检索工具的唯一定义(只定义一份,问答与出题共用):
 *  - rag(query, kb_name):知识库向量 + BM25 检索,返回带 [source-N] 的段落原文。
 * 这里负责 schema、提示词清单、参数校验、名称 → id 映射、检索与渲染、来源元数据;
 * 调用方(问答 / 出题)只决定结果如何回填(原文 / 反思压缩)。编号由 {@link SourceRegistry} 在一次
 * 对话回合或一次出题任务内统一分配。
 */
@Component
public class RetrievalTools {

    public static final int RAG_TOP_K = 5;
    public static final int SNIPPET_CHARS = 120;

    /** 本次挂载的知识库(由调用方按各自规则解析并鉴权) */
    public record Mounts(List<KnowledgeBaseRef> knowledgeBases) {
        public static final Mounts NONE = new Mounts(List.of());

        public boolean isEmpty() {
            return knowledgeBases.isEmpty();
        }
    }

    public record Outcome(String content, List<Map<String, Object>> sources, String summary, boolean isError) {
        static Outcome error(String content) {
            return new Outcome(content, List.of(), content, true);
        }
    }

    public static final class SourceRegistry {
        private final List<Map<String, Object>> sources = new ArrayList<>();

        public synchronized String register(Map<String, Object> source) {
            String ref = "source-" + (sources.size() + 1);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ref", ref);
            entry.putAll(source);
            sources.add(entry);
            return ref;
        }

        public synchronized List<Map<String, Object>> all() {
            return List.copyOf(sources);
        }
    }

    private final KnowledgeBaseRetrievalService knowledgeBases;
    private final ObjectMapper objectMapper;

    public RetrievalTools(KnowledgeBaseRetrievalService knowledgeBases, ObjectMapper objectMapper) {
        this.knowledgeBases = knowledgeBases;
        this.objectMapper = objectMapper;
    }

    public List<ToolSpecification> definitions(Mounts mounts) {
        if (mounts.knowledgeBases().isEmpty()) {
            return List.of();
        }
        return List.of(ToolSpecification.builder()
                .name("rag")
                .description("在挂载的某个知识库中检索相关段落。每个知识库调用一次,需要查多个知识库时按 kb_name 分别调用。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "非空自然语言查询;如果第一次检索效果不佳,换个查询角度")
                        .addEnumProperty("kb_name", mounts.knowledgeBases().stream().map(KnowledgeBaseRef::name).toList(),
                                "要检索的知识库,必须是已挂载知识库之一")
                        .required("query", "kb_name")
                        .additionalProperties(false)
                        .build())
                .build());
    }

    public static String toolList(Mounts mounts) {
        List<String> entries = new ArrayList<>();
        if (!mounts.knowledgeBases().isEmpty()) {
            entries.add("- `rag` — 在挂载的某个知识库中检索相关内容。\n"
                    + "    适用场景: 当答案大概率来自课程知识库(定义、公式、概念解释、教材内容)时使用;"
                    + "需要查多个知识库时按 kb_name 分别调用;第一次检索效果不佳就换个查询角度再判断是否无结果。\n"
                    + "    参数格式: {\"query\": \"非空自然语言查询\", \"kb_name\": \"挂载的某个知识库名称\"}。");
        }
        return entries.isEmpty() ? "- 无" : String.join("\n", entries);
    }

    public static String mountNote(Mounts mounts, String noneText) {
        List<String> lines = new ArrayList<>();
        if (!mounts.knowledgeBases().isEmpty()) {
            lines.add("已挂载知识库:" + String.join("、", mounts.knowledgeBases().stream().map(KnowledgeBaseRef::name).toList())
                    + "。调用 rag 时,kb_name 必须从其中选一个。");
        }
        return lines.isEmpty() ? noneText : String.join("\n", lines);
    }

    /** 执行一次工具调用:解析参数 → 校验 → 检索 → 渲染;任何参数问题以错误文本返回(模型可据此修正) */
    public Outcome execute(long courseId, Mounts mounts, SourceRegistry registry, String name, String argumentsJson) {
        JsonNode args;
        try {
            args = objectMapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception exception) {
            return Outcome.error("参数不是合法 JSON 对象:" + argumentsJson);
        }
        String query = args.path("query").asText("").strip();
        if (query.isEmpty()) {
            return Outcome.error("query 不能为空。");
        }
        return switch (name) {
            case "rag" -> rag(courseId, mounts, registry, query, args.path("kb_name").asText("").strip());
            default -> Outcome.error("未知工具:" + name);
        };
    }

    public Outcome rag(long courseId, Mounts mounts, SourceRegistry registry, String query, String kbName) {
        KnowledgeBaseRef kb = mounts.knowledgeBases().stream().filter(k -> k.name().equals(kbName)).findFirst().orElse(null);
        if (kb == null) {
            return Outcome.error("kb_name 必须是已挂载的知识库之一:"
                    + String.join("、", mounts.knowledgeBases().stream().map(KnowledgeBaseRef::name).toList()));
        }
        List<RetrievedPassage> passages = knowledgeBases.retrieve(courseId, kb.id(), query, RAG_TOP_K);
        if (passages.isEmpty()) {
            return new Outcome("知识库「" + kb.name() + "」中没有与「" + query + "」相关的内容。", List.of(),
                    "「" + kb.name() + "」无相关内容:" + query, false);
        }
        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (RetrievedPassage passage : passages) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("kind", "kb");
            source.put("kbId", passage.kbId());
            source.put("kbName", passage.kbName());
            source.put("documentName", passage.documentName());
            source.put("section", passage.section());
            source.put("snippet", snippet(passage.content()));
            String ref = registry.register(source);
            sources.add(withRef(source, ref));
            text.append('[').append(ref).append("] ").append(passage.documentName());
            if (passage.section() != null && !passage.section().isBlank()) {
                text.append(" › ").append(passage.section());
            }
            text.append('\n').append(passage.content()).append("\n\n");
        }
        return new Outcome(text.toString().strip(), sources,
                "「" + kb.name() + "」命中 " + passages.size() + " 段:" + query, false);
    }

    public static String snippet(String content) {
        String flat = content.replaceAll("\\s+", " ").strip();
        return Text.abbreviate(flat, SNIPPET_CHARS);
    }

    private static Map<String, Object> withRef(Map<String, Object> source, String ref) {
        Map<String, Object> registered = new LinkedHashMap<>(source);
        registered.put("ref", ref);
        return registered;
    }
}
