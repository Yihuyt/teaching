package cn.utcy.teaching.ai.document;

import cn.utcy.teaching.ai.infrastructure.AiMineruProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MinerU 云端真机冒烟(手动触发:环境变量 MINERU_LIVE_TOKEN_FILE 指向仓库外的 token 文件):
 * 真实 token(不回显)走完整链路——建任务 → 轮询 → 下载 zip → 取 full.md。
 * 解析对象为公开小 PDF,秒级完成。
 */
class MineruLiveSmokeTest {

    private static final String SAMPLE_PDF =
            "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf";

    @Test
    @EnabledIfEnvironmentVariable(named = "MINERU_LIVE_TOKEN_FILE", matches = ".+")
    void 真机解析_公开小PDF() throws Exception {
        String token = Files.readString(Path.of(System.getenv("MINERU_LIVE_TOKEN_FILE")), StandardCharsets.UTF_8).trim();
        assertThat(token).isNotBlank();

        MineruClient client = new MineruClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                new AiMineruProperties("https://mineru.net"));

        String markdown = client.parseToMarkdown(token, SAMPLE_PDF,
                message -> System.out.println("[MINERU-LIVE] " + message),
                () -> false);

        System.out.println("[MINERU-LIVE] markdown(" + markdown.length() + " chars) = "
                + markdown.substring(0, Math.min(200, markdown.length())).replace('\n', ' '));
        assertThat(markdown).isNotBlank();
    }
}
