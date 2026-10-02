package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.knowledgegraph.domain.BuildPreview;
import cn.utcy.teaching.knowledgegraph.domain.SectionSlicer;
import cn.utcy.teaching.knowledgegraph.domain.TocEntry;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgegraphProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class BuildDocuments {

    private static final TypeReference<List<String>> PAGES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> TASKS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final KnowledgegraphProperties properties;

    BuildDocuments(ObjectMapper objectMapper, KnowledgegraphProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    List<String> readPages(KnowledgeGraphBuildEntity build) {
        try {
            return objectMapper.readValue(build.getPageMarkdownJson(), PAGES_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的分页文本不是有效 JSON", exception);
        }
    }

    /** 已提交的 MinerU 任务(段键 → 任务 id),没有即空 */
    Map<String, String> readMineruTasks(KnowledgeGraphBuildEntity build) {
        if (build.getMineruTasksJson() == null) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(build.getMineruTasksJson(), TASKS_TYPE));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的 MinerU 任务登记不是有效 JSON", exception);
        }
    }

    List<TocEntry> readConfirmed(KnowledgeGraphBuildEntity build) {
        return entries(readJson(build.getTocConfirmedJson(), "目录确认稿").path("entries"));
    }

    static List<TocEntry> entries(JsonNode array) {
        List<TocEntry> result = new ArrayList<>();
        for (JsonNode entry : array) {
            result.add(new TocEntry(entry.path("number").asText(""),
                    entry.path("title").asText(""),
                    entry.path("level").asInt(1),
                    entry.path("page").asInt(0),
                    entry.path("endPage").asInt(0)));
        }
        return result;
    }

    BuildPreview readPreview(KnowledgeGraphBuildEntity build) {
        if (build.getPreviewJson() == null) {
            throw new IllegalStateException("预览文档缺失");
        }
        try {
            return objectMapper.readValue(build.getPreviewJson(), BuildPreview.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的预览文档不是有效 JSON", exception);
        }
    }

    /** 小节序号 → 正文:与 saveToc 同一切分,分页文本不可变所以结果一致 */
    Map<Integer, String> sliceTexts(KnowledgeGraphBuildEntity build, List<TocEntry> entries) {
        List<SectionSlicer.Slice> slices = SectionSlicer.slice(entries, readPages(build), properties.maxSectionChars());
        Map<Integer, String> texts = new LinkedHashMap<>();
        for (int i = 0; i < slices.size(); i++) {
            texts.put(i, slices.get(i).text());
        }
        return texts;
    }

    static String bookTitle(KnowledgeGraphBuildEntity build) {
        String name = build.getMaterialName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    String writeDraft(TocRecognitionService.TocDraft draft) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("degraded", draft.degraded());
        root.put("tocPages", draft.tocPages());
        root.put("notes", draft.notes());
        root.put("entries", draft.entries());
        return writeJson(root);
    }

    String writeConfirmed(List<TocEntry> entries) {
        return writeJson(Map.of("entries", entries));
    }

    String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("构建数据序列化失败", exception);
        }
    }

    JsonNode readJson(String json, String what) {
        if (json == null) {
            throw new IllegalStateException(what + "缺失");
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的" + what + "不是有效 JSON", exception);
        }
    }
}
