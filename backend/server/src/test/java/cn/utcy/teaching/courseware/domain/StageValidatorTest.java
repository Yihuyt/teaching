package cn.utcy.teaching.courseware.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StageValidatorTest {

    private static List<String> blockErrors(List<Block> blocks, String sceneType) {
        return StageProblem.forModel(StageValidator.validateBlocks(blocks, sceneType));
    }

    private static List<String> speechErrors(List<Stage.SpeechSegment> speech, String sceneType, List<Block> blocks) {
        return StageProblem.forModel(StageValidator.validateSpeech(speech, sceneType, blocks));
    }

    private static List<String> sceneErrors(Stage.Scene scene) {
        return StageProblem.forModel(StageValidator.validateScene(scene));
    }

    private static List<String> layoutErrors(List<Stage.BlockLayout> layouts, String sceneType, List<Block> blocks) {
        return StageProblem.forModel(StageValidator.validateLayouts(layouts, sceneType, blocks));
    }

    private static Block heading(String id) {
        return heading(id, "标题");
    }

    private static Block heading(String id, String text) {
        return new Block.Heading(id, 1, text);
    }

    private static Block bullets(String id, int n) {
        List<Block.BulletItem> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            items.add(new Block.BulletItem("条目 " + (i + 1), null));
        }
        return new Block.Bullets(id, null, items);
    }

    @Test
    @DisplayName("接受合法块列表")
    void acceptsValidBlocks() {
        assertThat(blockErrors(
                List.of(heading("blk-heading-1"), bullets("blk-bullets-1", 3)), "content"))
                .isEmpty();
    }

    @Test
    @DisplayName("拒绝重复 id(含 columns 子块)")
    void rejectsDuplicateIdsIncludingColumnsChildren() {
        List<Block> blocks = List.of(
                heading("blk-heading-1"),
                new Block.Columns("blk-columns-1", null, List.of(
                        List.of(heading("blk-heading-1")),
                        List.of(bullets("blk-bullets-1", 2)))));
        List<String> errors = blockErrors(blocks, "content");
        assertThat(errors).anyMatch(e -> e.contains("重复"));
    }

    @Test
    @DisplayName("拒绝表格行列不齐")
    void rejectsRaggedTableRows() {
        List<Block> blocks = List.of(new Block.Table("blk-table-1",
                List.of("a", "b"),
                List.of(List.of("1", "2"), List.of("只有一列")),
                null));
        assertThat(blockErrors(blocks, "content")).hasSize(1);
    }

    @Test
    @DisplayName("拒绝 chart 数据点与 categories 不齐")
    void rejectsChartDataLengthMismatch() {
        List<Block> blocks = List.of(new Block.Chart("blk-chart-1", "bar",
                List.of("甲", "乙", "丙"),
                List.of(new Block.ChartSeries("s", List.of(1.0, 2.0))),
                null));
        assertThat(blockErrors(blocks, "content")).hasSize(1);
    }

    @Test
    @DisplayName("拒绝 content 页上的 quiz_choice")
    void rejectsQuizChoiceOnContentScene() {
        List<Block> blocks = List.of(new Block.QuizChoice("blk-quiz_choice-1", "题干",
                List.of(new Block.QuizOption("A", "甲"), new Block.QuizOption("B", "乙")),
                List.of("A"), false, "讲解"));
        assertThat(blockErrors(blocks, "content"))
                .anyMatch(e -> e.contains("quiz"));
        assertThat(blockErrors(blocks, "quiz")).isEmpty();
    }

    @Test
    @DisplayName("拒绝 answer 不在选项中/单选多答案")
    void rejectsAnswerOutsideOptionsAndSingleChoiceMultiAnswer() {
        List<Block> blocks = List.of(new Block.QuizChoice("blk-quiz_choice-1", "题干",
                List.of(new Block.QuizOption("A", "甲"), new Block.QuizOption("B", "乙")),
                List.of("A", "C"), false, "讲解"));
        List<String> errors = blockErrors(blocks, "quiz");
        assertThat(errors).anyMatch(e -> e.contains("\"C\""));
        assertThat(errors).anyMatch(e -> e.contains("单选"));
    }

    @Test
    @DisplayName("拒绝 emphasis 文本为空或换行")
    void rejectsEmptyOrMultilineEmphasis() {
        assertThat(blockErrors(List.of(new Block.Emphasis("blk-emphasis-1", "", "说明")), "content"))
                .hasSize(1);
        assertThat(blockErrors(List.of(new Block.Emphasis("blk-emphasis-2", "一行\n两行", null)), "content"))
                .hasSize(1);
        assertThat(blockErrors(List.of(new Block.Emphasis("blk-emphasis-3", "一句话", null)), "content"))
                .isEmpty();
    }

    private static List<Block> speechSceneBlocks() {
        return List.of(heading("blk-heading-1"), bullets("blk-bullets-1", 3));
    }

    @Test
    @DisplayName("collectBlockIds 记录 bullets 条目数")
    void collectBlockIdsRecordsBulletsItemCount() {
        Map<String, Integer> ids = StageValidator.collectBlockIds(speechSceneBlocks());
        assertThat(ids.get("blk-heading-1")).isEqualTo(0);
        assertThat(ids.get("blk-bullets-1")).isEqualTo(3);
    }

    @Test
    @DisplayName("接受合法动作目标(含 #条目)")
    void acceptsValidActionTargets() {
        List<Stage.SpeechSegment> speech = List.of(new Stage.SpeechSegment("看这里", List.of(
                new Action.Highlight("blk-heading-1"),
                new Action.Reveal("blk-bullets-1#2"),
                new Action.Pause(500)), null));
        assertThat(speechErrors(speech, "content", speechSceneBlocks())).isEmpty();
    }

    @Test
    @DisplayName("拒绝不存在的目标、越界条目、非法 pause")
    void rejectsBadTargetsAndPause() {
        List<Stage.SpeechSegment> speech = List.of(new Stage.SpeechSegment("出错的段", List.of(
                new Action.Highlight("blk-ghost-1"),
                new Action.Reveal("blk-bullets-1#4"),
                new Action.Highlight("blk-heading-1#1"),
                new Action.Pause(50)), null));
        assertThat(speechErrors(speech, "content", speechSceneBlocks())).hasSize(4);
    }

    @Test
    @DisplayName("拒绝 interactive 页上的块目标动作(只允许 pause)")
    void rejectsBlockTargetActionsOnInteractiveScene() {
        List<Stage.SpeechSegment> speech = List.of(new Stage.SpeechSegment("大家动手试试。", List.of(
                new Action.Highlight("blk-heading-1"),
                new Action.Pause(800)), null));
        assertThat(speechErrors(speech, "interactive", List.of())).hasSize(1);
    }

    @Test
    @DisplayName("video 页要有 video.src 且没有块;别的页不能带 video;讲稿只许 pause")
    void videoSceneRules() {
        Stage.Scene video = new Stage.Scene("v1", "video", "实验视频", "standard", "看清楚", List.of(), List.of(),
                List.of(), null, new Stage.Video("courseware/1/videos/a.mp4"));
        assertThat(sceneErrors(video)).isEmpty();
        Stage.Scene noSrc = new Stage.Scene("v1", "video", "实验视频", "standard", "看清楚", List.of(), List.of(),
                List.of(), null, new Stage.Video(" "));
        assertThat(sceneErrors(noSrc)).anyMatch(e -> e.contains("video.src"));
        Stage.Scene content = new Stage.Scene("c1", "content", "讲解", "standard", "x",
                List.of(new Block.Paragraph("blk-paragraph-1", "正文")), List.of(), List.of(), null,
                new Stage.Video("courseware/1/videos/a.mp4"));
        assertThat(sceneErrors(content)).anyMatch(e -> e.contains("不应携带 video"));
        List<Stage.SpeechSegment> speech = List.of(new Stage.SpeechSegment("请看视频。", List.of(
                new Action.Highlight("blk-heading-1")), null));
        assertThat(speechErrors(speech, "video", List.of())).hasSize(1);
    }

    @Test
    @DisplayName("拒绝空 content 页与缺 quiz_choice 的 quiz 页")
    void rejectsEmptyContentSceneAndQuizSceneWithoutQuizChoice() {
        Stage.Scene empty = new Stage.Scene("p1", "content", "空页", "standard", null,
                List.of(), List.of(), List.of(), null);
        assertThat(sceneErrors(empty)).anyMatch(e -> e.contains("没有任何块"));

        Stage.Scene quiz = new Stage.Scene("p2", "quiz", "测验", "quiz", null,
                List.of(heading("blk-heading-1")), List.of(), List.of(), null);
        assertThat(sceneErrors(quiz)).anyMatch(e -> e.contains("quiz_choice"));
    }

    @Test
    @DisplayName("拒绝无 html 的 interactive 页")
    void rejectsInteractiveSceneWithoutHtml() {
        Stage.Scene scene = new Stage.Scene("p3", "interactive", "仿真", "standard", null,
                List.of(), List.of(), List.of(), null);
        assertThat(sceneErrors(scene)).anyMatch(e -> e.contains("interactive.html"));
    }

    @Test
    @DisplayName("排版覆盖规则")
    void layouts() {
        List<Block> blocks = List.of(heading("blk-heading-1"),
                new Block.Image("blk-image-1", "courseware/1/images/a.png", 4, 3, null),
                new Block.Columns("blk-columns-1", null, List.of(
                        List.of(new Block.Paragraph("blk-paragraph-1", "左")),
                        List.of(new Block.Paragraph("blk-paragraph-2", "右")))));
        assertThat(layoutErrors(List.of(
                new Stage.BlockLayout("blk-heading-1", new Stage.PinFrame(0, 0, 300, null), "large"),
                new Stage.BlockLayout("blk-image-1", new Stage.PinFrame(800, 400, 400, 240.0), null),
                new Stage.BlockLayout("blk-columns-1", null, "small")), "content", blocks)).isEmpty();

        List<String> errors = layoutErrors(List.of(
                new Stage.BlockLayout("blk-nope", null, "large"),
                new Stage.BlockLayout("blk-paragraph-1", null, "large"),
                new Stage.BlockLayout("blk-heading-1", null, "large"),
                new Stage.BlockLayout("blk-heading-1", null, "large"),
                new Stage.BlockLayout("blk-image-1", null, null),
                new Stage.BlockLayout("blk-columns-1", null, "huge")), "content", blocks);
        assertThat(errors).hasSize(5);
        assertThat(errors.get(0)).contains("不存在");
        assertThat(errors.get(2)).contains("重复");
        assertThat(errors.get(3)).contains("至少要给");
        assertThat(errors.get(4)).contains("非法");

        List<String> frames = layoutErrors(List.of(
                new Stage.BlockLayout("blk-heading-1", new Stage.PinFrame(1200, 0, 300, null), null),
                new Stage.BlockLayout("blk-image-1", new Stage.PinFrame(0, 0, 50, null), null),
                new Stage.BlockLayout("blk-columns-1", new Stage.PinFrame(0, 0, 300, null), null)), "content", blocks);
        assertThat(frames).anyMatch(e -> e.contains("超出页面"));
        assertThat(frames).anyMatch(e -> e.contains("宽度不能小于"));
        assertThat(frames).anyMatch(e -> e.contains("必须给高度"));
        assertThat(frames).anyMatch(e -> e.contains("columns 不能钉住"));
        assertThat(layoutErrors(List.of(
                new Stage.BlockLayout("blk-heading-1", new Stage.PinFrame(0, 0, 300, 100.0), null)), "content", blocks))
                .singleElement().asString().contains("不能给 h");
        assertThat(layoutErrors(List.of(
                new Stage.BlockLayout("x", null, "large")), "interactive", List.of()))
                .singleElement().asString().contains("interactive");
    }

    @Test
    @DisplayName("教师会遇到的问题带教师说法,编辑界面产生不了的问题没有")
    void teacherFacingProblems() {
        List<StageProblem> paragraph = StageValidator.validateBlocks(List.of(heading("blk-heading-1"),
                new Block.Paragraph("blk-paragraph-1", "第一行\n第二行")), "content");
        assertThat(paragraph).singleElement().satisfies(problem -> {
            assertThat(problem.forModel()).contains("blocks[1]");
            assertThat(problem.forTeacher()).isEqualTo("第 2 个内容块:段落里不能换行,要分段请再加一个段落");
        });

        List<StageProblem> speech = StageValidator.validateSpeech(List.of(
                new Stage.SpeechSegment("看这里。", List.of(), null),
                new Stage.SpeechSegment("由 $F=ma$ 可知", List.of(), null)), "content", speechSceneBlocks());
        assertThat(speech).singleElement().extracting(StageProblem::forTeacher).asString()
                .startsWith("第 2 段讲稿会被朗读");

        Stage.Scene empty = new Stage.Scene("p1", "content", "空页", "standard", null,
                List.of(), List.of(), List.of(), null);
        assertThat(StageValidator.validateScene(empty)).extracting(StageProblem::forTeacher)
                .containsExactly("这一页至少要保留一个内容块");

        List<StageProblem> table = StageValidator.validateBlocks(List.of(new Block.Table("blk-table-1",
                List.of("a", "b"), List.of(List.of("只有一列")), null)), "content");
        assertThat(table).singleElement().satisfies(problem -> assertThat(problem.teacherFacing()).isFalse());
    }

    @Test
    @DisplayName("测验页讲稿说出答案不归内容校验管(那是对模型的写作要求)")
    void quizSpeechMayStateTheAnswer() {
        List<Block> blocks = List.of(new Block.QuizChoice("blk-quiz_choice-1", "题干",
                List.of(new Block.QuizOption("A", "甲"), new Block.QuizOption("B", "乙")),
                List.of("B"), false, "讲解"));
        assertThat(StageValidator.validateSpeech(List.of(
                new Stage.SpeechSegment("这道题正确答案是 B。", List.of(), null)), "quiz", blocks)).isEmpty();
    }
}
