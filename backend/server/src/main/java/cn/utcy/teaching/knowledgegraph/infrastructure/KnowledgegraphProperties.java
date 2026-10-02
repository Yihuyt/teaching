package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.ai.llm.ModelSettings;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 教材构建知识图谱的生成配置。密钥与端点归 ai 模块;抽取要忠实原文,温度取低值。
 * 构建由请求直启在虚拟线程上执行,不设平台侧并发上限(任务几乎都在等外部服务,
 * 模型服务的限流即自然背压);每本构建内的模型调用并行 sectionConcurrency 路。
 * progressTimeout 内进展心跳没有更新即视为已中断(进程消失或挂死),由判滞巡检判失败,教师可从断点重试。
 */
@Validated
@ConfigurationProperties("teaching.knowledgegraph")
public record KnowledgegraphProperties(
        @NotBlank String model,
        double temperature,
        double topP,
        int maxOutputTokens,
        boolean reasoning,
        /** 单个抽取子片的正文字符上限,超长叶按段落均分 */
        @Min(1000) int maxSectionChars,
        /** 分页文本总量上限(字符):超限明确报错"教材解析文本过大" */
        @Min(100_000) int maxBookChars,
        /** 教材页数上限:下载原件读出真实页数后、提交 MinerU 之前检查,超限明确报错 */
        @Min(1) int maxBookPages,
        /** 合并语义召回的嵌入模型;向量只在合并过程中用,不落库 */
        @NotBlank String embeddingModel,
        @Min(1) int sectionConcurrency,
        @NotNull Duration progressTimeout
)implements ModelSettings {
}
