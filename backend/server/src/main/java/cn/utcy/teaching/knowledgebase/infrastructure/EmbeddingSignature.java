package cn.utcy.teaching.knowledgebase.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * embedding 签名:(model, dimension) → 8 位 hex。签名不匹配时业务侧提示"需重建索引"而不是报错。
 * 物理索引名 = kb-{kbId}-{signature}-{创建时刻};当前生效的物理索引名存在
 * knowledge_base.active_index_name 上——重建先建新物理索引,成功后切指针再清旧索引,在用索引从不被就地删改。
 */
public final class EmbeddingSignature {

    private EmbeddingSignature() {
    }

    public static String of(String model, int dimension) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (model + "#" + dimension).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", exception);
        }
    }

    public static String newIndexName(long knowledgeBaseId, String signature) {
        return "kb-" + knowledgeBaseId + "-" + signature + "-" + Long.toString(System.currentTimeMillis(), 36);
    }
}
