package cn.utcy.teaching.knowledgebase.infrastructure;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 课程知识库的入库与检索配置。ES 凭据经 secrets/configtree 注入;
 * 向量化密钥是用户级配置,不在这里。
 * progressTimeout 内进展心跳没有更新即视为入库已中断,由判滞巡检判失败,教师可重试。
 */
@Validated
@ConfigurationProperties("teaching.knowledgebase")
public record KnowledgebaseProperties(
        @NotNull Es es,
        @NotBlank String embeddingModel,
        @Min(1) int embeddingDimension,
        @Min(1) int embeddingBatchSize,
        @Min(1) int chunkTargetChars,
        @Min(1) int chunkMaxChars,
        @Min(0) int chunkOverlapChars,
        @NotNull Duration progressTimeout
) {

    public record Es(
            @NotBlank String uri,
            @NotBlank String username,
            String password,
            @NotNull Duration connectTimeout
    ) {
    }
}
