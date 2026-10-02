package cn.utcy.teaching.ai.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;

/** PDF 页数读取(纯函数):页数上限校验与 page_ranges 分段都以它为准 */
public final class PdfPages {

    private PdfPages() {
    }

    public static int pageCount(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取 PDF 文件:" + exception.getMessage(), exception);
        }
    }
}
