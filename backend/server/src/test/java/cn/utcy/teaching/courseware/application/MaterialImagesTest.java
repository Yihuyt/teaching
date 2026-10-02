package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.courseware.domain.Block;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialImagesTest {

    @Test
    void 可用图片描述_视觉切片标见附图_其余给描述() {
        MaterialImages images = MaterialImages.of(List.of(
                new SceneGenerator.ImageInput("courseware/9/images/a.jpg", "光路图", 884, 424, "用户选定的配图", false),
                new SceneGenerator.ImageInput("courseware/9/images/b.png", "", 300, 300, "生成的配图", true)));
        String text = images.describeAll(true);
        assertThat(text).startsWith("- **img_1**:用户选定的配图 | 尺寸 884×424(宽高比 2.08) [见附图] | 光路图\n");
        assertThat(text).contains("- **img_2**:生成的配图 | 尺寸 300×300(宽高比 1.00) [见附图] | 必须放上本页");
        assertThat(text).doesNotContain("img_1**:用户选定的配图 | 尺寸 884×424(宽高比 2.08) [见附图] | 必须");
        assertThat(images.describeAll(false)).doesNotContain("[见附图]");
        assertThat(images.get("img_1").objectKey()).isEqualTo("courseware/9/images/a.jpg");
        assertThat(images.get("img_2").contentType()).isEqualTo("image/png");
        assertThat(MaterialImages.empty().describeAll(true)).isEqualTo("(无可用图片)");
    }

    @Test
    void 从页面现有图片块重建可用图片() {
        MaterialImages images = MaterialImages.fromBlocks(List.of(
                new Block.Paragraph("blk-paragraph-1", "文"),
                new Block.Columns("blk-columns-1", null, List.of(
                        List.of(new Block.Image("blk-image-1", "courseware/9/images/img_3.png", 300, 200, "图")),
                        List.of(new Block.Paragraph("blk-paragraph-2", "文"))))));
        assertThat(images.ids()).containsExactly("img_1");
        assertThat(images.get("img_1").objectKey()).isEqualTo("courseware/9/images/img_3.png");
        assertThat(images.get("img_1").source()).isEqualTo("本页原有");
        assertThat(images.get("img_1").description()).isEqualTo("图");
    }

    @Test
    void 必须放上页面的图没被引用就点名() {
        MaterialImages images = MaterialImages.of(List.of(
                new SceneGenerator.ImageInput("courseware/9/images/a.jpg", "光路图", 884, 424, "素材", false),
                new SceneGenerator.ImageInput("courseware/9/images/gen.png", "示意图", 1664, 928, "按大纲画的配图", true)));
        List<Block> blocks = List.of(
                new Block.Paragraph("blk-paragraph-1", "文"),
                new Block.Columns("blk-columns-1", null, List.of(
                        List.of(new Block.Image("blk-image-1", "img_1", 0, 0, null)),
                        List.of(new Block.Paragraph("blk-paragraph-2", "文")))));
        assertThat(MaterialImages.imageSrcs(blocks)).containsExactly("img_1");
        assertThat(images.requiredButUnused(MaterialImages.imageSrcs(blocks))).containsExactly("img_2");
        assertThat(images.requiredButUnused(java.util.Set.of("img_1", "img_2"))).isEmpty();
    }
}
