package cn.utcy.teaching.courseware.domain;

import java.util.List;

/**
 * 课件的只读投影:把不该给某个受众看的信息去掉。
 * 测验答案与讲解只属于判分:学生播放视图、课堂问答语境、讲稿撰写模型看到的都是剥掉答案的题块——
 * 讲稿模型看不到答案,就不可能把答案念出来。
 */
public final class StageProjections {

    private StageProjections() {
    }

    public static List<Block> withoutQuizSecrets(List<Block> blocks) {
        return blocks.stream().map(block -> {
            if (block instanceof Block.QuizChoice quiz) {
                return (Block) new Block.QuizChoice(quiz.id(), quiz.stem(), quiz.options(), List.of(),
                        quiz.multiple(), "");
            }
            return block;
        }).toList();
    }

    public static Stage.Scene withoutQuizSecrets(Stage.Scene scene) {
        return new Stage.Scene(scene.id(), scene.type(), scene.title(), scene.preset(), scene.summary(),
                withoutQuizSecrets(scene.blocks()), scene.speech(), scene.layouts(), scene.interactive(), scene.video());
    }

    public static Stage withoutQuizSecrets(Stage stage) {
        return new Stage(stage.title(), stage.theme(),
                stage.scenes().stream().map(StageProjections::withoutQuizSecrets).toList());
    }
}
