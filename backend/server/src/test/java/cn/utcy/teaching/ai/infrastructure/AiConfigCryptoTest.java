package cn.utcy.teaching.ai.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiConfigCryptoTest {

    @TempDir
    Path dir;

    private AiConfigCrypto crypto(int keyBytes) throws Exception {
        Path file = dir.resolve("key-" + keyBytes);
        byte[] raw = new byte[keyBytes];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) i;
        }
        Files.writeString(file, Base64.getEncoder().encodeToString(raw) + "\n",
                StandardCharsets.UTF_8);
        return new AiConfigCrypto(new AiConfigProperties(file));
    }

    @Test
    void 加解密往返_密文含随机IV每次不同() throws Exception {
        AiConfigCrypto crypto = crypto(32);
        String secret = "sk-abc123";
        byte[] first = crypto.encrypt(secret);
        byte[] second = crypto.encrypt(secret);
        assertThat(first).isNotEqualTo(second);
        assertThat(crypto.decrypt(first)).isEqualTo(secret);
        assertThat(crypto.decrypt(second)).isEqualTo(secret);
    }

    @Test
    void 密钥长度不是32字节_启动即炸() {
        assertThatThrownBy(() -> crypto(16))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    void 密钥文件缺失_启动即炸() {
        assertThatThrownBy(() -> new AiConfigCrypto(new AiConfigProperties(dir.resolve("nope"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("读取失败");
    }
}
