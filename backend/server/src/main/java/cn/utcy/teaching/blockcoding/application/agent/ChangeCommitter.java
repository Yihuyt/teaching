package cn.utcy.teaching.blockcoding.application.agent;

import java.util.ArrayList;
import java.util.List;

public final class ChangeCommitter {
    public record Committed(ScriptChange change, String blockId) {
    }

    public record CommitReport(List<Committed> scripts, List<SpriteChange> sprites, List<VariableChange> variables) {
        public static CommitReport empty() {
            return new CommitReport(List.of(), List.of(), List.of());
        }
    }

    public static final class CommitFailed extends RuntimeException {
        private final CommitReport report;

        CommitFailed(String message, Throwable cause, CommitReport report) {
            super(message, cause);
            this.report = report;
        }

        public CommitReport report() {
            return report;
        }
    }

    private ChangeCommitter() {
    }

    public static CommitReport commit(ProjectDiff diff, Browser browser) {
        List<Committed> scripts = new ArrayList<>();
        List<SpriteChange> sprites = new ArrayList<>();
        List<VariableChange> variables = new ArrayList<>(diff.createdVariables());
        try {
            for (Sprite sprite : diff.createdSprites()) {
                browser.createSprite(sprite.name());
                sprites.add(new SpriteChange(SpriteChange.Kind.CREATED, sprite, false));
            }
            for (ScriptChange change : diff.scripts()) {
                switch (change.kind()) {
                    case DELETED -> {
                        // 编辑器里要删的是原来那段(previous):本轮先改写再删除的,script 是内存里的新版,没有编辑器 id
                        Script previous = change.previous();
                        browser.removeScript(previous.sprite(), previous.blockId());
                        scripts.add(new Committed(change, null));
                    }
                    case REPLACED -> {
                        Script previous = change.previous();
                        browser.removeScript(previous.sprite(), previous.blockId());
                        scripts.add(new Committed(change, browser.insertScript(change.script())));
                    }
                    case WRITTEN -> scripts.add(new Committed(change, browser.insertScript(change.script())));
                }
            }
            for (VariableChange change : diff.deletedVariables()) {
                browser.deleteVariable(change.sprite(), change.name(), change.list());
                variables.add(change);
            }
            for (SpriteChange change : diff.deletedSprites()) {
                browser.deleteSprite(change.sprite().name());
                sprites.add(change);
            }
        } catch (RuntimeException exception) {
            throw new CommitFailed("写进作品时出错:" + exception.getMessage(), exception,
                    new CommitReport(List.copyOf(scripts), List.copyOf(sprites), List.copyOf(variables)));
        }
        return new CommitReport(List.copyOf(scripts), List.copyOf(sprites), List.copyOf(variables));
    }
}
