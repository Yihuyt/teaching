package cn.utcy.teaching.courseware.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * 块(Block)联合 —— 课件内容的唯一词汇表,与前端 features/courseware/dsl/blocks.ts 逐字段对齐。
 * JSON 形状必须与 TS 端完全一致(字段名、可选字段缺省即省略),Jackson 按 type 多态。
 * 判别字段 "type" 完全由 @JsonTypeInfo 读写,type() 访问器不参与序列化。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Block.Heading.class, name = "heading"),
        @JsonSubTypes.Type(value = Block.Paragraph.class, name = "paragraph"),
        @JsonSubTypes.Type(value = Block.Bullets.class, name = "bullets"),
        @JsonSubTypes.Type(value = Block.Formula.class, name = "formula"),
        @JsonSubTypes.Type(value = Block.Code.class, name = "code"),
        @JsonSubTypes.Type(value = Block.Table.class, name = "table"),
        @JsonSubTypes.Type(value = Block.Chart.class, name = "chart"),
        @JsonSubTypes.Type(value = Block.Emphasis.class, name = "emphasis"),
        @JsonSubTypes.Type(value = Block.Image.class, name = "image"),
        @JsonSubTypes.Type(value = Block.Callout.class, name = "callout"),
        @JsonSubTypes.Type(value = Block.Columns.class, name = "columns"),
        @JsonSubTypes.Type(value = Block.QuizChoice.class, name = "quiz_choice")
})
public sealed interface Block
        permits Block.Heading, Block.Paragraph, Block.Bullets, Block.Formula, Block.Code,
        Block.Table, Block.Chart, Block.Emphasis, Block.Image, Block.Callout, Block.Columns, Block.QuizChoice {

    String id();

    @JsonIgnore
    String type();

    record Heading(String id, int level, String text) implements Block {
        @Override
        public String type() {
            return "heading";
        }
    }

    record Paragraph(String id, String text) implements Block {
        @Override
        public String type() {
            return "paragraph";
        }
    }

    record BulletItem(String text, List<String> sub) {
    }

    record Bullets(String id, Boolean ordered, List<BulletItem> items) implements Block {
        @Override
        public String type() {
            return "bullets";
        }
    }

    record Formula(String id, String latex, String caption) implements Block {
        @Override
        public String type() {
            return "formula";
        }
    }

    record Code(String id, String language, String code, String caption) implements Block {
        @Override
        public String type() {
            return "code";
        }
    }

    record Table(String id, List<String> headers, List<List<String>> rows, String caption)
            implements Block {
        @Override
        public String type() {
            return "table";
        }
    }

    record ChartSeries(String name, List<Double> data) {
    }

    record Chart(String id, String chartType, List<String> categories, List<ChartSeries> series,
                 String caption) implements Block {
        @Override
        public String type() {
            return "chart";
        }
    }

    record Emphasis(String id, String text, String caption) implements Block {
        @Override
        public String type() {
            return "emphasis";
        }
    }

    /**
     * 图片块:src 为课件名下的对象键(素材图片或文生图产物),width/height 为原图像素尺寸,
     * 布局按原始宽高比排版。播放地址不在文档里——视图另给 对象键 → 预签名 URL 的映射。
     */
    record Image(String id, String src, int width, int height, String caption) implements Block {
        @Override
        public String type() {
            return "image";
        }
    }

    record Callout(String id, String variant, String title, String text) implements Block {
        @Override
        public String type() {
            return "callout";
        }
    }

    record Columns(String id, List<Double> ratio, List<List<Block>> children) implements Block {
        @Override
        public String type() {
            return "columns";
        }
    }

    record QuizOption(String label, String text) {
    }

    record QuizChoice(String id, String stem, List<QuizOption> options, List<String> answer,
                      boolean multiple, String explanation) implements Block {
        @Override
        public String type() {
            return "quiz_choice";
        }
    }
}
