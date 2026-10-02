package cn.utcy.teaching.resource.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OssConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsTheSingleSupportedCredentialFileFormat() throws Exception {
        Path credentials = temporaryDirectory.resolve("credentials");
        Files.writeString(credentials, """
                ALIYUN_OSS_ACCESS_KEY_ID=test-id
                ALIYUN_OSS_ACCESS_KEY_SECRET=test-secret
                """);

        OssConfiguration.Credentials parsed = OssConfiguration.readCredentials(credentials);

        assertThat(parsed.accessKeyId()).isEqualTo("test-id");
        assertThat(parsed.accessKeySecret()).isEqualTo("test-secret");
    }

    @Test
    void rejectsLeadingOrTrailingWhitespaceInCredentialValues() throws Exception {
        Path credentials = temporaryDirectory.resolve("credentials");
        Files.writeString(credentials, """
                ALIYUN_OSS_ACCESS_KEY_ID= test-id
                ALIYUN_OSS_ACCESS_KEY_SECRET=test-secret
                """);

        assertThatThrownBy(() -> OssConfiguration.readCredentials(credentials))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OSS AccessKey ID 与 AccessKey Secret 均不能为空且首尾不能包含空白");
    }

    @Test
    void rejectsSymbolicLinkCredentialPath() throws Exception {
        Path target = temporaryDirectory.resolve("target");
        Files.writeString(target, """
                ALIYUN_OSS_ACCESS_KEY_ID=test-id
                ALIYUN_OSS_ACCESS_KEY_SECRET=test-secret
                """);
        Path link = temporaryDirectory.resolve("credentials");
        Files.createSymbolicLink(link, target.getFileName());

        assertThatThrownBy(() -> OssConfiguration.readCredentials(link))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OSS 密钥路径必须是普通文件且不能是符号链接");
    }
}
