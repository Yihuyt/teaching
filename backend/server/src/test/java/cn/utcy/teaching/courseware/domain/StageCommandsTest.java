package cn.utcy.teaching.courseware.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编辑操作应用内核:17 种操作的语义、空形防护(宽松解析产生的 null 不得炸成 NPE)、
 * 被改动页与生成流水线同一套规范化、任一错误整批拒绝。
 */
class StageCommandsTest {

    private static Stage.Scene contentScene(String id, String title) {
        return new Stage.Scene(id, "content", title, "standard", "概要",
                List.of(new Block.Paragraph("blk-paragraph-1", "正文一"),
                        new Block.Paragraph("blk-paragraph-2", "正文二")),
                List.of(new Stage.SpeechSegment("讲稿。", List.of(), "courseware/1/audio/a.wav")), List.of(), null);
    }

    private static Stage baseStage() {
        return new Stage("课", "default", List.of(contentScene("scene-1", "一"), contentScene("scene-2", "二")));
    }

    @Test
    @DisplayName("元信息与页结构操作:改标题/移页/改页元信息/删页")
    void metaAndStructureOps() {
        Stage applied = StageCommands.apply(baseStage(), List.of(
                new EditOp.UpdateStageMeta("新课名"),
                new EditOp.MoveScene("scene-2", 0),
                new EditOp.UpdateSceneMeta("scene-1", "改过的一", null, null),
                new EditOp.DeleteScene("scene-2")));
        assertThat(applied.title()).isEqualTo("新课名");
        assertThat(applied.scenes()).hasSize(1);
        assertThat(applied.scenes().get(0).title()).isEqualTo("改过的一");
    }

    @Test
    @DisplayName("add_scene:新页 id 顺现有最大序号分配,块缺 id 由规范化补齐")
    void addSceneAllocatesIdAndNormalizesBlocks() {
        Stage stage = new Stage("课", "default",
                List.of(contentScene("scene-1", "一"), contentScene("scene-7", "七")));
        Stage applied = StageCommands.apply(stage, List.of(
                new EditOp.AddScene(9, "新页", "content", "standard", "本页要点",
                        List.of(new Block.Paragraph(null, "内容")))));
        Stage.Scene added = applied.scenes().get(2);
        assertThat(added.id()).isEqualTo("scene-8");
        assertThat(added.blocks().get(0).id()).isNotBlank();
    }

    @Test
    @DisplayName("概要可不填:三种加页都能不带概要,改页元信息传空串清空概要、传 null 不动")
    void summaryIsOptional() {
        String page = "<!DOCTYPE html><html><body>仿真</body></html>";
        Stage applied = StageCommands.apply(baseStage(), List.of(
                new EditOp.AddScene(9, "新页", "content", "standard", null,
                        List.of(new Block.Paragraph(null, "内容"))),
                new EditOp.AddInteractiveScene(9, "交互", "  ", page, "simulation"),
                new EditOp.AddVideoScene(9, "视频", null, "courseware/1/videos/v.mp4")));
        assertThat(applied.scenes().subList(2, 5)).allSatisfy(scene -> assertThat(scene.summary()).isNull());

        Stage kept = StageCommands.apply(baseStage(), List.of(
                new EditOp.UpdateSceneMeta("scene-1", "改名", null, null)));
        assertThat(kept.scenes().get(0).summary()).isEqualTo("概要");

        Stage cleared = StageCommands.apply(baseStage(), List.of(
                new EditOp.UpdateSceneMeta("scene-1", null, null, "")));
        assertThat(cleared.scenes().get(0).title()).isEqualTo("一");
        assertThat(cleared.scenes().get(0).summary()).isNull();

        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.UpdateSceneMeta("scene-1", null, null, null))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("没有要修改的内容");
    }

    @Test
    @DisplayName("整页校验:教师能改的问题给教师说法并带页名;编辑界面产生不了的内容按格式错误拒绝")
    void sceneProblemsSplitByAudience() {
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.ReplaceBlock("scene-1", "blk-paragraph-1", new Block.Paragraph("blk-paragraph-1", "一\n二")))))
                .isInstanceOf(StageCommands.Rejected.class)
                .hasMessage("第 1 页「一」:第 1 个内容块:段落里不能换行,要分段请再加一个段落");
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.DeleteBlock("scene-1", "blk-paragraph-1"),
                new EditOp.DeleteBlock("scene-1", "blk-paragraph-2"))))
                .isInstanceOf(StageCommands.Rejected.class)
                .hasMessage("第 1 页「一」:这一页至少要保留一个内容块");
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.PinBlock("scene-1", "blk-paragraph-1", 0.0, 0.0, 300.0, 100.0))))
                .isInstanceOf(StageCommands.Malformed.class)
                .hasMessageContaining("第 1 页「一」").hasMessageContaining("不能给 h");
    }

    @Test
    @DisplayName("教师写的测验页讲稿可以说出答案")
    void teacherQuizSpeechMayStateTheAnswer() {
        Stage.Scene quiz = new Stage.Scene("scene-1", "quiz", "小测", "quiz", null,
                List.of(new Block.QuizChoice("blk-quiz_choice-1", "题干",
                        List.of(new Block.QuizOption("A", "甲"), new Block.QuizOption("B", "乙")),
                        List.of("B"), false, "讲解")),
                List.of(new Stage.SpeechSegment("读题。", List.of(), null)), List.of(), null);
        Stage applied = StageCommands.apply(new Stage("课", "default", List.of(quiz)), List.of(
                new EditOp.SetSpeech("scene-1", List.of(new EditOp.SpeechSegmentInput("正确答案是 B。", List.of())))));
        assertThat(applied.scenes().get(0).speech().get(0).text()).isEqualTo("正确答案是 B。");
    }

    @Test
    @DisplayName("块操作:增/替/移/删,目标不存在整批拒绝")
    void blockOps() {
        Stage applied = StageCommands.apply(baseStage(), List.of(
                new EditOp.AddBlock("scene-1", 99, new Block.Paragraph("blk-paragraph-9", "尾块")),
                new EditOp.MoveBlock("scene-1", "blk-paragraph-9", 0),
                new EditOp.ReplaceBlock("scene-1", "blk-paragraph-1",
                        new Block.Paragraph("blk-paragraph-1", "换了")),
                new EditOp.DeleteBlock("scene-1", "blk-paragraph-2")));
        List<Block> blocks = applied.scenes().get(0).blocks();
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).id()).isEqualTo("blk-paragraph-9");
        assertThat(((Block.Paragraph) blocks.get(1)).text()).isEqualTo("换了");

        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.DeleteBlock("scene-1", "blk-nope"))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("set_speech:文本未变的段保留原音频,新文本段音频为空")
    void setSpeechKeepsAudioForUnchangedText() {
        Stage applied = StageCommands.apply(baseStage(), List.of(
                new EditOp.SetSpeech("scene-1", List.of(
                        new EditOp.SpeechSegmentInput("讲稿。", List.of()),
                        new EditOp.SpeechSegmentInput("新加的一段。", List.of())))));
        List<Stage.SpeechSegment> speech = applied.scenes().get(0).speech();
        assertThat(speech.get(0).audioPath()).isEqualTo("courseware/1/audio/a.wav");
        assertThat(speech.get(1).audioPath()).isNull();
    }

    @Test
    @DisplayName("删除被讲稿动作引用的块:动作被清洗掉,不报错")
    void dropsActionsPointingToDeletedBlock() {
        Stage.Scene scene = new Stage.Scene("scene-1", "content", "一", "standard", "概要",
                List.of(new Block.Paragraph("blk-paragraph-1", "正文一"),
                        new Block.Paragraph("blk-paragraph-2", "正文二")),
                List.of(new Stage.SpeechSegment("看这里。",
                        List.of(new Action.Highlight("blk-paragraph-2")), null)), List.of(), null);
        Stage stage = new Stage("课", "default", List.of(scene));
        Stage applied = StageCommands.apply(stage, List.of(
                new EditOp.DeleteBlock("scene-1", "blk-paragraph-2")));
        assertThat(applied.scenes().get(0).speech().get(0).actions()).isEmpty();
    }

    @Test
    @DisplayName("教师自备的交互网页:格式契约同生成(单文档 / 禁联网 / CDN 白名单),通过即成页并做 KaTeX 后处理")
    void interactiveHtmlOps() {
        String page = "<!DOCTYPE html><html><body><p>能量 $E=mc^2$</p>"
                + "<script src=\"https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js\"></script></body></html>";
        Stage applied = StageCommands.apply(baseStage(), List.of(
                new EditOp.AddInteractiveScene(1, "抛体仿真", "调角度看轨迹", page, "simulation")));
        Stage.Scene added = applied.scenes().get(1);
        assertThat(added.type()).isEqualTo("interactive");
        assertThat(added.preset()).isEqualTo("standard");
        assertThat(added.interactive().widgetType()).isEqualTo("simulation");
        assertThat(added.interactive().html()).contains("katex.min.js").contains("\\(E=mc^2\\)");

        Stage replaced = StageCommands.apply(applied, List.of(
                new EditOp.SetInteractiveHtml(added.id(), "<!DOCTYPE html><html><body>新版本</body></html>")));
        assertThat(replaced.scenes().get(1).interactive().html()).contains("新版本");
        assertThat(replaced.scenes().get(1).interactive().widgetType()).isEqualTo("simulation");

        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.AddInteractiveScene(0, "联网", "x", "<html><body><script>fetch('/a')</script></body></html>", null))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessage("网页里不能联网读取数据或嵌入其他网页(出现了:fetch()");
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.AddInteractiveScene(0, "外链", "x", "<html><body><img src=\"https://evil.example.com/a.png\"></body></html>", null))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("不允许的外部地址:evil.example.com");
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.AddInteractiveScene(0, "残缺", "x", "<div>不是完整文档</div>", null))))
                .isInstanceOf(StageCommands.Rejected.class)
                .hasMessage("网页不完整,需要从 <html> 开始、到 </html> 结束的完整网页");
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.SetInteractiveHtml("scene-1", "<html></html>"))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("不是交互页");
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.AddInteractiveScene(0, "空", "x", null, null))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("视频页:上传后的对象键建页 / 换视频;非视频页不能换视频;讲解页不能用 add_scene 建视频页")
    void videoSceneOps() {
        Stage applied = StageCommands.apply(baseStage(), List.of(
                new EditOp.AddVideoScene(2, "实验视频", "看清楚反射角", "courseware/1/videos/a.mp4")));
        Stage.Scene added = applied.scenes().get(2);
        assertThat(added.type()).isEqualTo("video");
        assertThat(added.preset()).isEqualTo("standard");
        assertThat(added.blocks()).isEmpty();
        assertThat(added.video().src()).isEqualTo("courseware/1/videos/a.mp4");

        Stage replaced = StageCommands.apply(applied, List.of(
                new EditOp.SetVideo(added.id(), "courseware/1/videos/b.mp4")));
        assertThat(replaced.scenes().get(2).video().src()).isEqualTo("courseware/1/videos/b.mp4");

        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.SetVideo("scene-1", "courseware/1/videos/b.mp4"))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("不是视频页");
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.AddVideoScene(0, "空", "x", "  "))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("先上传视频");
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.AddScene(0, "视频", "video", "standard", "x", List.of(new Block.Paragraph("b1", "x"))))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("页面类型不正确");
    }

    @Test
    @DisplayName("宽松解析产生的空形:一律是校验错误,绝不 NPE")
    void nullShapesAreErrorsNotNpe() {
        record Case(EditOp op, String fragment) {
        }
        List<Case> cases = List.of(
                new Case(new EditOp.AddScene(0, "题", null, null, "概要",
                        List.of(new Block.Paragraph("b1", "x"))), "页面类型不正确"),
                new Case(new EditOp.AddScene(0, "题", "content", null, "概要",
                        List.of(new Block.Paragraph("b1", "x"))), "布局不适用"),
                new Case(new EditOp.AddScene(0, "题", "content", "standard", "概要",
                        Arrays.asList((Block) null)), "内容块不能为空"),
                new Case(new EditOp.AddBlock("scene-1", 0, null), "内容块不能为空"),
                new Case(new EditOp.ReplaceBlock("scene-1", "blk-paragraph-1", null), "内容块不能为空"),
                new Case(new EditOp.SetSpeech("scene-1",
                        Arrays.asList((EditOp.SpeechSegmentInput) null)), "每一段都要有文字"),
                new Case(new EditOp.SetSpeech("scene-1",
                        List.of(new EditOp.SpeechSegmentInput(null, List.of()))), "每一段都要有文字"),
                new Case(new EditOp.DeleteBlock("scene-1", null), "不存在"),
                new Case(new EditOp.DeleteScene(null), "不存在"),
                new Case(new EditOp.UpdateStageMeta(null), "课件标题不能为空"));
        for (Case c : cases) {
            assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(c.op())))
                    .as(c.op().op() + " → " + c.fragment())
                    .isInstanceOf(StageCommands.Rejected.class)
                    .hasMessageContaining(c.fragment());
        }
    }

    @Test
    @DisplayName("排版覆盖:钉住 / 改字号档 / 解除钉住,条目合并且每块至多一条")
    void layoutOps() {
        Stage applied = StageCommands.apply(baseStage(), List.of(
                new EditOp.PinBlock("scene-1", "blk-paragraph-1", 100.0, 200.0, 500.0, null),
                new EditOp.SetBlockSize("scene-1", "blk-paragraph-1", "large"),
                new EditOp.SetBlockSize("scene-1", "blk-paragraph-2", "small")));
        List<Stage.BlockLayout> layouts = applied.scenes().get(0).layouts();
        assertThat(layouts).hasSize(2);
        Stage.BlockLayout first = layouts.stream().filter(l -> l.blockId().equals("blk-paragraph-1")).findFirst().orElseThrow();
        assertThat(first.frame()).isEqualTo(new Stage.PinFrame(100, 200, 500, null));
        assertThat(first.size()).isEqualTo("large");

        // 解除钉住保留字号档;字号档回 normal 且无钉住即整条删除
        Stage unpinned = StageCommands.apply(applied, List.of(
                new EditOp.UnpinBlock("scene-1", "blk-paragraph-1"),
                new EditOp.SetBlockSize("scene-1", "blk-paragraph-2", "normal")));
        List<Stage.BlockLayout> after = unpinned.scenes().get(0).layouts();
        assertThat(after).containsExactly(new Stage.BlockLayout("blk-paragraph-1", null, "large"));

        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.UnpinBlock("scene-1", "blk-paragraph-1"))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("没有被钉住");
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.PinBlock("scene-1", "blk-paragraph-1", null, 0.0, 300.0, null))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("缺少钉住的位置");
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.SetBlockSize("scene-1", "blk-paragraph-1", "huge"))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("字号档");
        // 钉住帧本身不合规(文字块带 h)由整页校验拦下;画布不会产生这种帧,按格式错误拒绝
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.PinBlock("scene-1", "blk-paragraph-1", 0.0, 0.0, 300.0, 100.0))))
                .isInstanceOf(StageCommands.Malformed.class).hasMessageContaining("不能给 h");
    }

    @Test
    @DisplayName("删块连带删它的排版覆盖;替换块换 id 时覆盖跟着改名")
    void layoutsFollowBlocks() {
        Stage pinned = StageCommands.apply(baseStage(), List.of(
                new EditOp.PinBlock("scene-1", "blk-paragraph-2", 100.0, 200.0, 500.0, null)));
        Stage deleted = StageCommands.apply(pinned, List.of(
                new EditOp.DeleteBlock("scene-1", "blk-paragraph-2")));
        assertThat(deleted.scenes().get(0).layouts()).isEmpty();

        Stage renamed = StageCommands.apply(pinned, List.of(
                new EditOp.ReplaceBlock("scene-1", "blk-paragraph-2",
                        new Block.Paragraph("blk-paragraph-9", "换了"))));
        assertThat(renamed.scenes().get(0).layouts())
                .containsExactly(new Stage.BlockLayout("blk-paragraph-9", new Stage.PinFrame(100, 200, 500, null), null));
    }

    @Test
    @DisplayName("整批原子性:批里任何一个错误整批拒绝;改动后不合格的页也整批拒绝")
    void atomicity() {
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.UpdateStageMeta("先改个名"),
                new EditOp.DeleteScene("scene-nope"))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("已不存在");
        // 删光一页的块:操作本身合法,但改动后的页过不了整页校验
        assertThatThrownBy(() -> StageCommands.apply(baseStage(), List.of(
                new EditOp.DeleteBlock("scene-1", "blk-paragraph-1"),
                new EditOp.DeleteBlock("scene-1", "blk-paragraph-2"))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("至少要保留一个内容块");
    }

    @Test
    @DisplayName("页数上限与 quiz 预设搭配约束")
    void limitsAndPresetRules() {
        List<Stage.Scene> scenes = new ArrayList<>();
        for (int i = 1; i <= StageCommands.MAX_SCENES; i++) {
            scenes.add(contentScene("scene-" + i, "第" + i));
        }
        Stage full = new Stage("课", "default", List.copyOf(scenes));
        assertThatThrownBy(() -> StageCommands.apply(full, List.of(
                new EditOp.AddScene(99, "溢出", "content", "standard", "概要",
                        List.of(new Block.Paragraph("b", "x"))))))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("最多");
        assertThat(StageCommands.validatePreset("quiz", "standard")).contains("quiz");
        assertThat(StageCommands.validatePreset("content", "quiz")).contains("content");
        assertThat(StageCommands.validatePreset("content", "standard")).isNull();
    }
}
