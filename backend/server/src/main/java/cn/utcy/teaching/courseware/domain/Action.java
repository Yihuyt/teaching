package cn.utcy.teaching.courseware.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 讲课动作 —— 与前端 courseware/dsl/actions.ts 对齐(3 种)。
 * 动作挂在讲稿段上,段首触发;目标为块 id,bullets 条目用 `blk-x#n`。
 * 判别字段 "type" 完全由 @JsonTypeInfo 读写,type() 访问器不参与序列化。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Action.Highlight.class, name = "highlight"),
        @JsonSubTypes.Type(value = Action.Reveal.class, name = "reveal"),
        @JsonSubTypes.Type(value = Action.Pause.class, name = "pause")
})
public sealed interface Action
        permits Action.Highlight, Action.Reveal, Action.Pause {

    @JsonIgnore
    String type();

    record Highlight(String target) implements Action {
        @Override
        public String type() {
            return "highlight";
        }
    }

    record Reveal(String target) implements Action {
        @Override
        public String type() {
            return "reveal";
        }
    }

    record Pause(int ms) implements Action {
        @Override
        public String type() {
            return "pause";
        }
    }
}
