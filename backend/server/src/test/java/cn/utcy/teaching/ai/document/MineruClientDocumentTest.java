package cn.utcy.teaching.ai.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class MineruClientDocumentTest {

    @Test
    void 抽取全文与图片() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", png);
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            put(zip, "full.md", "# 标题\n![](images/a.png)".getBytes(StandardCharsets.UTF_8));
            put(zip, "images/a.png", png.toByteArray());
            put(zip, "doc_content_list.json", ("[{\"type\":\"text\",\"page_idx\":0},"
                    + "{\"type\":\"image\",\"img_path\":\"images/a.png\",\"image_caption\":[\"光路图\"],\"page_idx\":2}]")
                    .getBytes(StandardCharsets.UTF_8));
        }

        MineruClient.ParsedDocument doc = MineruClient.extractDocument(zipBytes.toByteArray(), new ObjectMapper());

        assertThat(doc.markdown()).startsWith("# 标题");
        assertThat(doc.pageCount()).isEqualTo(2);
        assertThat(doc.images()).singleElement().satisfies(image -> {
            assertThat(image.path()).isEqualTo("images/a.png");
            assertThat(image.contentType()).isEqualTo("image/png");
            assertThat(image.pageNumber()).isEqualTo(3);
            assertThat(image.description()).isEqualTo("光路图");
            assertThat(image.width()).isEqualTo(40);
            assertThat(image.height()).isEqualTo(20);
        });
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
