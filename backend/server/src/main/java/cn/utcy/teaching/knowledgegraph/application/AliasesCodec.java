package cn.utcy.teaching.knowledgegraph.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/** 别名列的 JSON 编解码:空列表存 NULL(与 CHECK 约束「NULL 或数组」一致) */
@Component
public class AliasesCodec {

    private static final TypeReference<List<String>> LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public AliasesCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(aliases);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("别名无法序列化", exception);
        }
    }

    public List<String> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(json, LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的别名不是有效 JSON 数组", exception);
        }
    }
}
