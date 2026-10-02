package cn.utcy.teaching.knowledgebase.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingSignatureTest {

    @Test
    @DisplayName("同配置签名稳定,模型或维度变化即换签名")
    void signatureTracksConfig() {
        String base = EmbeddingSignature.of("text-embedding-v4", 1024);
        assertThat(base).hasSize(8).matches("[0-9a-f]{8}");
        assertThat(EmbeddingSignature.of("text-embedding-v4", 1024)).isEqualTo(base);
        assertThat(EmbeddingSignature.of("text-embedding-v3", 1024)).isNotEqualTo(base);
        assertThat(EmbeddingSignature.of("text-embedding-v4", 768)).isNotEqualTo(base);
    }

    @Test
    @DisplayName("索引名 = kb-{id}-{signature}")
    void indexName() {
        assertThat(EmbeddingSignature.newIndexName(7, "abcd1234")).startsWith("kb-7-abcd1234-");
    }
}
