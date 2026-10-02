package cn.utcy.teaching.courseware.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * 课件的编辑操作词汇表 —— 教师手工编辑的每个改动都是其中一条,应用与校验在 {@link StageCommands}。
 * 与 frontend/src/features/courseware/dsl/ops.ts 的 EditOp 联合对齐。
 * 判别字段 "op" 完全由 @JsonTypeInfo 读写,op() 访问器不参与序列化。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EditOp.UpdateStageMeta.class, name = "update_stage_meta"),
        @JsonSubTypes.Type(value = EditOp.AddScene.class, name = "add_scene"),
        @JsonSubTypes.Type(value = EditOp.AddInteractiveScene.class, name = "add_interactive_scene"),
        @JsonSubTypes.Type(value = EditOp.AddVideoScene.class, name = "add_video_scene"),
        @JsonSubTypes.Type(value = EditOp.DeleteScene.class, name = "delete_scene"),
        @JsonSubTypes.Type(value = EditOp.MoveScene.class, name = "move_scene"),
        @JsonSubTypes.Type(value = EditOp.UpdateSceneMeta.class, name = "update_scene_meta"),
        @JsonSubTypes.Type(value = EditOp.AddBlock.class, name = "add_block"),
        @JsonSubTypes.Type(value = EditOp.ReplaceBlock.class, name = "replace_block"),
        @JsonSubTypes.Type(value = EditOp.DeleteBlock.class, name = "delete_block"),
        @JsonSubTypes.Type(value = EditOp.MoveBlock.class, name = "move_block"),
        @JsonSubTypes.Type(value = EditOp.SetSpeech.class, name = "set_speech"),
        @JsonSubTypes.Type(value = EditOp.SetInteractiveHtml.class, name = "set_interactive_html"),
        @JsonSubTypes.Type(value = EditOp.SetVideo.class, name = "set_video"),
        @JsonSubTypes.Type(value = EditOp.PinBlock.class, name = "pin_block"),
        @JsonSubTypes.Type(value = EditOp.UnpinBlock.class, name = "unpin_block"),
        @JsonSubTypes.Type(value = EditOp.SetBlockSize.class, name = "set_block_size")
})
public sealed interface EditOp
        permits EditOp.UpdateStageMeta, EditOp.AddScene, EditOp.AddInteractiveScene, EditOp.AddVideoScene,
        EditOp.DeleteScene, EditOp.MoveScene, EditOp.UpdateSceneMeta, EditOp.AddBlock, EditOp.ReplaceBlock,
        EditOp.DeleteBlock, EditOp.MoveBlock, EditOp.SetSpeech, EditOp.SetInteractiveHtml, EditOp.SetVideo,
        EditOp.PinBlock, EditOp.UnpinBlock, EditOp.SetBlockSize {

    @JsonIgnore
    String op();

    record UpdateStageMeta(String title) implements EditOp {
        @Override
        public String op() {
            return "update_stage_meta";
        }
    }

    /**
     * 新增一页(结构操作,讲解页 / 测验页):blocks 是这页的初始内容(至少一块);交互页走 add_interactive_scene。
     */
    record AddScene(int index, String title, String type, String preset, String summary, List<Block> blocks)
            implements EditOp {
        @Override
        public String op() {
            return "add_scene";
        }
    }

    /**
     * 新增一个交互页:html 是教师自己准备的自包含网页(格式契约见 {@link InteractiveHtml},落库前做 KaTeX 后处理);
     * widgetType 可选,只作标注。
     */
    record AddInteractiveScene(int index, String title, String summary, String html, String widgetType)
            implements EditOp {
        @Override
        public String op() {
            return "add_interactive_scene";
        }
    }

    record DeleteScene(String sceneId) implements EditOp {
        @Override
        public String op() {
            return "delete_scene";
        }
    }

    record MoveScene(String sceneId, int toIndex) implements EditOp {
        @Override
        public String op() {
            return "move_scene";
        }
    }

    record UpdateSceneMeta(String sceneId, String title, String preset, String summary)
            implements EditOp {
        @Override
        public String op() {
            return "update_scene_meta";
        }
    }

    record AddBlock(String sceneId, int index, Block block) implements EditOp {
        @Override
        public String op() {
            return "add_block";
        }
    }

    record ReplaceBlock(String sceneId, String blockId, Block block) implements EditOp {
        @Override
        public String op() {
            return "replace_block";
        }
    }

    record DeleteBlock(String sceneId, String blockId) implements EditOp {
        @Override
        public String op() {
            return "delete_block";
        }
    }

    record MoveBlock(String sceneId, String blockId, int toIndex) implements EditOp {
        @Override
        public String op() {
            return "move_block";
        }
    }

    /** 编辑操作里的讲稿段:只有文本与动作,音频归服务端(文本未变的段应用时保留原音频) */
    record SpeechSegmentInput(String text, List<Action> actions) {
    }

    record SetSpeech(String sceneId, List<SpeechSegmentInput> speech) implements EditOp {
        @Override
        public String op() {
            return "set_speech";
        }
    }

    /** 新增一个视频页:src 是教师上传到本课件名下的视频对象键(POST /videos 的返回) */
    record AddVideoScene(int index, String title, String summary, String src) implements EditOp {
        @Override
        public String op() {
            return "add_video_scene";
        }
    }

    /** 换掉视频页的视频(讲稿保留) */
    record SetVideo(String sceneId, String src) implements EditOp {
        @Override
        public String op() {
            return "set_video";
        }
    }

    /** 整份换掉交互页的网页(讲稿、组件标注保留) */
    record SetInteractiveHtml(String sceneId, String html) implements EditOp {
        @Override
        public String op() {
            return "set_interactive_html";
        }
    }

    /**
     * 钉住一个顶层块:位置与宽度由教师定,image / chart 还要给高度(其余块高度由内容量出,不能给 h)。
     * 已钉住的块再次 pin 即移动 / 改尺寸;字号档不受影响。数值用包装类型:宽松解析下缺项是校验错误而不是 0。
     */
    record PinBlock(String sceneId, String blockId, Double x, Double y, Double w, Double h) implements EditOp {
        @Override
        public String op() {
            return "pin_block";
        }
    }

    /** 解除钉住,块回到流式排版(字号档保留) */
    record UnpinBlock(String sceneId, String blockId) implements EditOp {
        @Override
        public String op() {
            return "unpin_block";
        }
    }

    /** 改一个顶层块的字号档(small / large / xlarge);normal 即恢复缺省 */
    record SetBlockSize(String sceneId, String blockId, String size) implements EditOp {
        @Override
        public String op() {
            return "set_block_size";
        }
    }
}
