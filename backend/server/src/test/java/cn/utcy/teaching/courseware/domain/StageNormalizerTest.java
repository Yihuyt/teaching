package cn.utcy.teaching.courseware.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StageNormalizerTest {

    @Test
    @DisplayName("补齐缺失 id 与重复 id,产物通过 validate")
    void fillsMissingAndDuplicateIdsAndPassesValidate() {
        List<Block> blocks = List.of(
                new Block.Heading("", 1, "A"),
                new Block.Heading("blk-heading-1", 2, "B"),
                new Block.Heading("blk-heading-1", 2, "C"));
        StageNormalizer.NormalizeResult<List<Block>> result = StageNormalizer.normalizeBlocks(blocks);
        assertThat(result.warnings()).hasSize(2);
        assertThat(result.value().stream().map(Block::id).distinct()).hasSize(3);
        assertThat(StageValidator.validateBlocks(result.value(), "content")).isEmpty();
    }

    @Test
    @DisplayName("截齐/补空表格行")
    void padsAndTruncatesTableRows() {
        List<Block> blocks = List.of(new Block.Table("blk-table-1",
                List.of("a", "b"),
                List.of(List.of("1"), List.of("1", "2", "3")),
                null));
        StageNormalizer.NormalizeResult<List<Block>> result = StageNormalizer.normalizeBlocks(blocks);
        Block.Table table = (Block.Table) result.value().get(0);
        assertThat(table.rows()).isEqualTo(List.of(
                List.of("1", ""),
                List.of("1", "2")));
        assertThat(result.warnings()).hasSize(2);
    }

    @Test
    @DisplayName("chart 数据点截齐/补 0")
    void padsChartDataWithZeros() {
        List<Block> blocks = List.of(new Block.Chart("blk-chart-1", "line",
                List.of("一", "二", "三"),
                List.of(new Block.ChartSeries("s", List.of(1.0))),
                null));
        StageNormalizer.NormalizeResult<List<Block>> result = StageNormalizer.normalizeBlocks(blocks);
        Block.Chart chart = (Block.Chart) result.value().get(0);
        assertThat(chart.series().get(0).data()).isEqualTo(List.of(1.0, 0.0, 0.0));
    }

    @Test
    @DisplayName("quiz 答案去重并纠正 multiple")
    void dedupesQuizAnswersAndCorrectsMultiple() {
        List<Block> blocks = List.of(new Block.QuizChoice("blk-quiz_choice-1", "题干",
                List.of(new Block.QuizOption("A", "甲"), new Block.QuizOption("B", "乙")),
                List.of("A", "A", "B"), false, "讲解"));
        StageNormalizer.NormalizeResult<List<Block>> result = StageNormalizer.normalizeBlocks(blocks);
        Block.QuizChoice quiz = (Block.QuizChoice) result.value().get(0);
        assertThat(quiz.answer()).isEqualTo(List.of("A", "B"));
        assertThat(quiz.multiple()).isTrue();
    }

    @Test
    @DisplayName("丢弃非法目标动作、保留合法动作、钳制 pause")
    void dropsInvalidActionsKeepsValidAndClampsPause() {
        List<Block> sceneBlocks = List.of(
                new Block.Heading("blk-heading-1", 1, "A"),
                new Block.Bullets("blk-bullets-1", null, List.of(
                        new Block.BulletItem("x", null),
                        new Block.BulletItem("y", null))));
        List<Stage.SpeechSegment> speech = List.of(
                new Stage.SpeechSegment("  第一段  ", List.of(
                        new Action.Highlight("blk-heading-1"),
                        new Action.Highlight("blk-ghost-9"),
                        new Action.Reveal("blk-bullets-1#5"),
                        new Action.Pause(60000)), null),
                new Stage.SpeechSegment("", List.of(), null));

        StageNormalizer.NormalizeResult<List<Stage.SpeechSegment>> result =
                StageNormalizer.normalizeSpeech(speech, "content", sceneBlocks);

        assertThat(result.value()).hasSize(1);
        assertThat(result.value().get(0).text()).isEqualTo("第一段");
        assertThat(result.value().get(0).actions()).isEqualTo(List.of(
                new Action.Highlight("blk-heading-1"),
                new Action.Pause(5000)));
        assertThat(result.warnings()).hasSize(4);
    }

    @Test
    void 空白audioPath清成null_预签名与TTS按无音频处理() {
        List<Block> sceneBlocks = List.of(new Block.Heading("blk-heading-1", 1, "A"));
        List<Stage.SpeechSegment> speech = List.of(
                new Stage.SpeechSegment("有空串音频路径的段", List.of(), ""),
                new Stage.SpeechSegment("有真实音频路径的段", List.of(), "courseware/1/audio/a.wav"));

        StageNormalizer.NormalizeResult<List<Stage.SpeechSegment>> result =
                StageNormalizer.normalizeSpeech(speech, "content", sceneBlocks);

        assertThat(result.value().get(0).audioPath()).isNull();
        assertThat(result.value().get(1).audioPath()).isEqualTo("courseware/1/audio/a.wav");
    }

    @Test
    @DisplayName("排版覆盖清洗:丢孤儿与空条目、重复取后者,每处带警告")
    void normalizeLayouts() {
        List<Block> blocks = List.of(new Block.Paragraph("blk-paragraph-1", "一"));
        StageNormalizer.NormalizeResult<List<Stage.BlockLayout>> result = StageNormalizer.normalizeLayouts(List.of(
                new Stage.BlockLayout("blk-nope", null, "large"),
                new Stage.BlockLayout("blk-paragraph-1", null, null),
                new Stage.BlockLayout("blk-paragraph-1", null, "small"),
                new Stage.BlockLayout("blk-paragraph-1", null, "large")), blocks);
        assertThat(result.value()).containsExactly(new Stage.BlockLayout("blk-paragraph-1", null, "large"));
        assertThat(result.warnings()).hasSize(3);
        assertThat(StageNormalizer.normalizeLayouts(null, blocks).value()).isEmpty();
    }
}
