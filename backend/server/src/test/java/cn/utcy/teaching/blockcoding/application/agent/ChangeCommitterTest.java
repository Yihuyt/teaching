package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.blockcoding.application.HarvestPayload;
import cn.utcy.teaching.blockcoding.application.agent.ScriptChange;
import cn.utcy.teaching.blockcoding.application.agent.Script;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeCommitterTest {
    private static Script script(int id, String sprite, String code, String blockId) {
        return Script.preexisting(id, sprite, code, "<xml/>", 2, List.of(), blockId);
    }

    private static HarvestPayload harvest(String sprite) {
        return new HarvestPayload(List.of(new HarvestPayload.SpriteContext(sprite, false, List.of(), List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of(), java.util.Map.of(), sprite, java.util.Map.of());
    }

    private static Script fresh(int id, String sprite, String code) {
        return new Script(id, sprite, code, "<xml/>", 2, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, false);
    }

    @Test
    @DisplayName("新写插入、改写先删旧再插新、删除只删;返回每处改动在编辑器里的新 id")
    void commitsInOrder() {
        FakeEditor editor = new FakeEditor();
        Script original = script(1, "Cat", "when green flag clicked\nmove (10) steps", "top-1");
        Script doomed = script(2, "Cat", "when [space v] key pressed\nsay [hi]", "top-2");
        editor.seed(List.of(original, doomed));
        List<ScriptChange> changes = List.of(
                new ScriptChange(ScriptChange.Kind.REPLACED, fresh(1, "Cat", "when green flag clicked\nmove (20) steps"), original),
                new ScriptChange(ScriptChange.Kind.DELETED, doomed, doomed),
                new ScriptChange(ScriptChange.Kind.WRITTEN, fresh(3, "Apple", "when green flag clicked\nhide"), null));
        Sprite apple = new Sprite("Apple", false, List.of("costume1"), List.of(), List.of(), List.of());

        List<ChangeCommitter.Committed> committed = ChangeCommitter.commit(
                new ProjectDiff(List.of(apple), changes, List.of(), List.of(), List.of()), editor).scripts();

        assertThat(committed).hasSize(3);
        assertThat(committed.get(0).blockId()).isEqualTo("ins-1");
        assertThat(committed.get(1).blockId()).isNull();
        assertThat(committed.get(2).blockId()).isEqualTo("ins-2");
        assertThat(editor.codes()).containsExactly("when green flag clicked\nmove (20) steps", "when green flag clicked\nhide");
    }

    @Test
    @DisplayName("本轮先改写再删除作品里原有的脚本:编辑器里删的是原来那段(按它的 id),新版不插")
    void deleteAfterRewriteRemovesTheOriginal() {
        FakeEditor editor = new FakeEditor();
        Script original = script(1, "Cat", "define draw array\nmove (10) steps", "top-1");
        editor.seed(List.of(original));
        Project project = Project.fromHarvest(harvest("Cat"), List.of(original));
        Script rewritten = fresh(1, "Cat", "define draw array\nmove (20) steps");
        project.replace(original, rewritten);
        project.delete(rewritten);

        List<ChangeCommitter.Committed> committed = ChangeCommitter.commit(project.diff(), editor).scripts();

        assertThat(committed).singleElement().satisfies(c -> {
            assertThat(c.change().kind()).isEqualTo(ScriptChange.Kind.DELETED);
            assertThat(c.change().previous()).isSameAs(original);
        });
        assertThat(editor.codes()).isEmpty();
    }

    @Test
    @DisplayName("中途失败:抛出的异常里带着失败前已落进去的部分,后面的不动")
    void partialFailureReportsWhatLanded() {
        FakeEditor editor = new FakeEditor() {
            @Override
            public String insertScript(Script script) {
                if (script.sprite().equals("Ghost")) {
                    throw new IllegalStateException("作品里没有角色 Ghost");
                }
                return super.insertScript(script);
            }
        };
        List<ScriptChange> changes = List.of(
                new ScriptChange(ScriptChange.Kind.WRITTEN, fresh(1, "Cat", "when green flag clicked\nhide"), null),
                new ScriptChange(ScriptChange.Kind.WRITTEN, fresh(2, "Ghost", "when green flag clicked\nshow"), null),
                new ScriptChange(ScriptChange.Kind.WRITTEN, fresh(3, "Cat", "when [space v] key pressed\nsay [hi]"), null));

        assertThatThrownBy(() -> ChangeCommitter.commit(new ProjectDiff(List.of(), changes, List.of(), List.of(), List.of()), editor))
                .isInstanceOf(ChangeCommitter.CommitFailed.class)
                .hasMessageContaining("作品里没有角色 Ghost")
                .satisfies(failed -> assertThat(((ChangeCommitter.CommitFailed) failed).report().scripts()).hasSize(1));
        assertThat(editor.codes()).containsExactly("when green flag clicked\nhide");
    }
}
