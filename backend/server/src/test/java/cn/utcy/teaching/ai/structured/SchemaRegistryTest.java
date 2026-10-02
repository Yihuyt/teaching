package cn.utcy.teaching.ai.structured;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaRegistryTest {

    private final SchemaRegistry registry = new SchemaRegistry(new ObjectMapper(),
            "courseware/schemas", java.util.List.of("scene-blocks", "speech", "answer"));
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("启动即装载全部 schema,原始 JsonNode 可用于嵌入 prompt")
    void loadsAllSchemas() {
        for (String key : new String[]{"scene-blocks", "speech", "answer"}) {
            assertThat(registry.rawSchema(key).isObject()).as("raw(%s)", key).isTrue();
            assertThat(registry.validator(key)).as("validator(%s)", key).isNotNull();
        }
    }

    @Test
    @DisplayName("scene-blocks 校验器:接受最小合法块树,拒绝未知字段")
    void pageBlocksValidatorWorks() throws JsonProcessingException {
        JsonSchema validator = registry.validator("scene-blocks");

        assertThat(validator.validate(mapper.readTree("""
                {"blocks":[{"id":"blk-heading-1","type":"heading","level":1,"text":"引言"}]}
                """))).isEmpty();

        assertThat(validator.validate(mapper.readTree("""
                {"blocks":[{"id":"blk-heading-1","type":"heading","level":1,"text":"引言","bogus":true}]}
                """))).isNotEmpty();
    }

    @Test
    @DisplayName("未注册的 key 抛错")
    void unknownKeyThrows() {
        assertThatThrownBy(() -> registry.rawSchema("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.validator("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
