package cn.utcy.teaching.courseware.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialBundleBuilderTest {

    private static MaterialBundleBuilder.Image img(String id, int scene, String desc, int w, int h) {
        return new MaterialBundleBuilder.Image(id, new byte[]{1}, "image/jpeg", scene, desc, w, h, null, 0, 0);
    }

    @Test
    void 公平预算_小文件不被大文件挤死() {
        int[] budgets = MaterialBundleBuilder.allocateDocumentTextBudgets(new int[]{100_000, 800}, 10_000);
        assertThat(budgets[1]).isEqualTo(800);
        assertThat(budgets[0]).isEqualTo(9_200);
        assertThat(MaterialBundleBuilder.allocateDocumentTextBudgets(new int[]{}, 10)).isEmpty();
        assertThat(MaterialBundleBuilder.allocateDocumentTextBudgets(new int[]{5}, 0)).containsExactly(0);
    }

    @Test
    void 词边界截断() {
        assertThat(MaterialBundleBuilder.truncateTextAtBoundary("hello world", 8)).isEqualTo("hello ");
        assertThat(MaterialBundleBuilder.truncateTextAtBoundary("短文", 10)).isEqualTo("短文");
        assertThat(MaterialBundleBuilder.truncateTextAtBoundary("abc", 0)).isEmpty();
    }

    @Test
    void 合并_来源头_图片重编号_文本引用改写_视觉轮转() {
        MaterialBundleBuilder.Part a = new MaterialBundleBuilder.Part("讲义.pdf", 1, "application/pdf", 3,
                "第一章 ![](images/a1.jpg) 内容 ![](images/a2.jpg)",
                List.of(img("images/a1.jpg", 1, "", 800, 400), img("images/a2.jpg", 2, "反射示意", 200, 200)));
        MaterialBundleBuilder.Part b = new MaterialBundleBuilder.Part("补充.docx", 2,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 0,
                "补充材料 ![](images/b1.png)", List.of(img("images/b1.png", 1, "", 100, 100)));

        MaterialBundleBuilder.Result result = MaterialBundleBuilder.build(List.of(b, a), 50_000, 2);

        assertThat(result.text()).startsWith("## 来源文档 1:讲义.pdf\n- 顺序:1\n- 类型:application/pdf\n- 页数:3\n\n");
        assertThat(result.text()).contains("![](img_1)").contains("![](img_2)").contains("![](img_3)")
                .contains(MaterialBundleBuilder.SECTION_SEPARATOR + "## 来源文档 2:补充.docx");
        assertThat(result.images()).extracting(MaterialBundleBuilder.Image::id).containsExactly("img_1", "img_2", "img_3");
        assertThat(result.images().get(0).sourceDocumentName()).isEqualTo("讲义.pdf");
        // 视觉名额 2:讲义里有图注的 img_2 先入选,然后轮到补充.docx 的 img_3;img_1 落选
        assertThat(result.images()).extracting(MaterialBundleBuilder.Image::visionPriority).containsExactly(0, 2, 1);
        assertThat(result.visionImageCount()).isEqualTo(2);
        assertThat(result.totalImageCount()).isEqualTo(3);
    }

    @Test
    void 图片引用改写只匹配整词() {
        assertThat(MaterialBundleBuilder.replaceImageIds("img_1 img_10 x-img_1", Map.of("img_1", "Z")))
                .isEqualTo("Z img_10 x-img_1");
    }
}
