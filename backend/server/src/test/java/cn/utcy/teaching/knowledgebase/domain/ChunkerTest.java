package cn.utcy.teaching.knowledgebase.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkerTest {

    private final Chunker chunker = new Chunker(100, 150, 30);

    @Test
    @DisplayName("markdown 标题维护 section 路径,标题是块边界")
    void headingsFormSectionPath() {
        String text = """
                # 第一章
                这是第一章的内容。
                ## 第一节
                这是第一节的内容。
                """;
        List<Chunker.Chunk> chunks = chunker.chunk(text);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).section()).isEqualTo("第一章");
        assertThat(chunks.get(0).content()).contains("第一章的内容");
        assertThat(chunks.get(1).section()).isEqualTo("第一章 › 第一节");
        assertThat(chunks.get(1).seq()).isEqualTo(1);
    }

    @Test
    @DisplayName("超过目标长度在句子边界切块,块长不超过硬上限")
    void splitsAtSentenceBoundary() {
        String sentence = "这里是一句大约二十个字符长度的示例文本。";
        String text = sentence.repeat(20);
        List<Chunker.Chunk> chunks = chunker.chunk(text);
        assertThat(chunks).hasSizeGreaterThan(1);
        for (Chunker.Chunk chunk : chunks) {
            assertThat(chunk.content().length()).isLessThanOrEqualTo(150 + 30 + 1);
            assertThat(chunk.content()).endsWith("。");
        }
    }

    @Test
    @DisplayName("无句子边界的超长文本按硬上限切,不产生超限块")
    void hardSplitsUnbrokenText() {
        String text = "字".repeat(1000);
        List<Chunker.Chunk> chunks = chunker.chunk(text);
        assertThat(chunks).isNotEmpty();
        for (Chunker.Chunk chunk : chunks) {
            assertThat(chunk.content().length()).isLessThanOrEqualTo(150 + 30);
        }
        String joined = String.join("", chunks.stream().map(Chunker.Chunk::content).toList());
        assertThat(joined).hasSize(1000); // 无边界文本没有整句重叠,内容无损不重复
    }

    @Test
    @DisplayName("空文本产出空列表;非法参数拒绝构造")
    void emptyAndInvalid() {
        assertThat(chunker.chunk("")).isEmpty();
        assertThat(chunker.chunk("   \n  ")).isEmpty();
        assertThatThrownBy(() -> new Chunker(100, 50, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker(100, 150, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("seq 连续递增")
    void seqIsSequential() {
        String text = "第一句话在这里结束。".repeat(50);
        List<Chunker.Chunk> chunks = chunker.chunk(text);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).seq()).isEqualTo(i);
        }
    }
}
