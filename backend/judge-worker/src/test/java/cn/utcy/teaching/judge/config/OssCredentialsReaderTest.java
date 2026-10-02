package cn.utcy.teaching.judge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OssCredentialsReaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsTheOnlySupportedTwoLineFormat() throws IOException {
        Path file = temporaryDirectory.resolve("aliyun-oss-credentials");
        Files.writeString(file, """
                ALIYUN_OSS_ACCESS_KEY_ID=test-id
                ALIYUN_OSS_ACCESS_KEY_SECRET=test-secret
                """);

        OssCredentials credentials = OssCredentialsReader.read(file);

        assertThat(credentials.accessKeyId()).isEqualTo("test-id");
        assertThat(credentials.accessKeySecret()).isEqualTo("test-secret");
    }

    @Test
    void rejectsAdditionalOrRenamedFields() throws IOException {
        Path file = temporaryDirectory.resolve("aliyun-oss-credentials");
        Files.writeString(file, """
                ACCESS_KEY_ID=test-id
                ALIYUN_OSS_ACCESS_KEY_SECRET=test-secret
                EXTRA=value
                """);

        assertThatThrownBy(() -> OssCredentialsReader.read(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("严格包含");
    }
}
