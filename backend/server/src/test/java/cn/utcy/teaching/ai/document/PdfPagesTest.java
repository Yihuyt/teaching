package cn.utcy.teaching.ai.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfPagesTest {

    @Test
    @DisplayName("读出真实页数;非 PDF 字节明确报错")
    void readsPageCount() throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < 5; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            assertThat(PdfPages.pageCount(out.toByteArray())).isEqualTo(5);
        }
        assertThatThrownBy(() -> PdfPages.pageCount("不是PDF".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法读取 PDF");
    }
}
