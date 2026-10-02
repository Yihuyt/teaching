package cn.utcy.teaching.ai.api;

import cn.utcy.teaching.ai.application.UserAiConfigService;

import cn.utcy.teaching.ai.application.UserAiConfigService.ConfigView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户级 AI 服务配置(账户设置面):大模型 API Key 与 MinerU 令牌。
 * 密钥永不回显:视图只含"已配置 + 尾号";任何登录用户只能配置自己的。
 */
@Validated
@RestController
@RequestMapping("/api/v1/account/ai-config")
public class UserAiConfigController {

    private final UserAiConfigService config;

    public UserAiConfigController(UserAiConfigService config) {
        this.config = config;
    }

    @GetMapping
    public ConfigView view() {
        return config.viewForCurrentUser();
    }

    public record UpdateLlmKeyRequest(
            @NotBlank(message = "API Key 不能为空")
            @Size(max = 500, message = "API Key 长度异常") String apiKey) {
    }

    @PutMapping("/llm")
    public ConfigView updateLlmKey(@Valid @RequestBody UpdateLlmKeyRequest request) {
        return config.updateLlmKey(request.apiKey());
    }

    @DeleteMapping("/llm")
    public ConfigView clearLlmKey() {
        return config.clearLlmKey();
    }

    public record UpdateMineruTokenRequest(
            /* MinerU token 是 JWT,实测 400+ 字符 */
            @NotBlank(message = "令牌不能为空")
            @Size(max = 2000, message = "令牌长度异常") String token) {
    }

    @PutMapping("/mineru")
    public ConfigView updateMineruToken(@Valid @RequestBody UpdateMineruTokenRequest request) {
        return config.updateMineruToken(request.token());
    }

    @DeleteMapping("/mineru")
    public ConfigView clearMineruToken() {
        return config.clearMineruToken();
    }
}
