package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.courseware.domain.Stage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Stage ↔ JSON 字符串编解码(数据库 body 列与 API 载荷共用)。
 * 坏 JSON 一律 BadRequest,不做静默兜底。
 */
@Component
public class StageJsonCodec {

    private final ObjectMapper objectMapper;

    public StageJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(Stage stage) {
        try {
            return objectMapper.writeValueAsString(stage);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("课件序列化失败", e);
        }
    }

    public Stage fromJson(String json) {
        try {
            return objectMapper.readValue(json, Stage.class);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("课件数据不是合法的 JSON 文档:" + e.getOriginalMessage());
        }
    }

    public Stage fromTree(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, Stage.class);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("课件数据不是合法的课件文档:" + e.getOriginalMessage());
        }
    }

    /** 域模型 → JsonNode(视图层在其上附加 audioUrl 等瞬态字段) */
    public ObjectNode toTree(Stage stage) {
        return objectMapper.valueToTree(stage);
    }
}
