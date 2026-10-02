package cn.utcy.teaching.ai.infrastructure;

import cn.utcy.teaching.ai.application.UserAiConfigService;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * DashScope 端点与超时参数。密钥不在这里——密钥是用户级配置
 * ({@link UserAiConfigService}),每次调用由业务侧解析后随请求传入。
 */
@Validated
@ConfigurationProperties("teaching.ai.dashscope")
public record DashScopeProperties(
        @NotNull URI baseUrl,
        /** DashScope 原生 API 端点(语音合成、文生图;与 chat 的 OpenAI 兼容端点不同源) */
        @NotNull URI nativeBaseUrl,
        @NotNull Duration connectTimeout,
        /** 上游静默超时:思考型模型会长时间无内容,reasoning 心跳会刷新它 */
        @NotNull Duration idleTimeout
) {
}
