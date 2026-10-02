package cn.utcy.teaching.ai.document;

import cn.utcy.teaching.shared.error.BadRequestException;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 文档文本抽取(知识库入库、出题附件共用):按文件类型路由——PDF 走 MinerU 云端(版面保真,全文不截断),
 * Office 文档用 POI 抽纯文本,md/txt 直读(UTF-8 失败回退 GBK)。
 * 不支持的类型明确报错,不做嗅探兜底。
 */
@Component
public class DocumentParser {

    /** 本地抽取的文件大小上限:POI 解析超大文件会耗尽堆内存 */
    private static final long MAX_LOCAL_BYTES = 50L * 1024 * 1024;
    /** 单次下载的墙钟:挂死的连接不能永久占住入库名额 */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    private final MineruClient mineru;
    private final HttpClient httpClient;

    public DocumentParser(MineruClient mineru) {
        this.mineru = mineru;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public static boolean supported(String name) {
        String suffix = suffixOf(name);
        return switch (suffix) {
            case "pdf", "docx", "pptx", "xlsx", "md", "markdown", "txt" -> true;
            default -> false;
        };
    }

    /**
     * @param mineruToken 仅 PDF 需要;调用方对 PDF 文档提前解析令牌(未配置在请求线程 400)
     * @param fileUrl     OSS 预签名地址(请求线程内取好)
     */
    public String parse(String name, String fileUrl, String mineruToken,
                        Consumer<String> onProgress, BooleanSupplier cancelled) {
        String suffix = suffixOf(name);
        return switch (suffix) {
            case "pdf" -> mineru.parseToMarkdown(mineruToken, fileUrl, onProgress, cancelled);
            case "docx" -> extractOffice(name, fileUrl, "docx");
            case "pptx" -> extractOffice(name, fileUrl, "pptx");
            case "xlsx" -> extractOffice(name, fileUrl, "xlsx");
            case "md", "markdown", "txt" -> decodeText(download(name, fileUrl));
            default -> throw new BadRequestException(
                    "暂不支持该文件类型,可入库的类型:pdf、docx、pptx、xlsx、md、txt");
        };
    }

    private String extractOffice(String name, String fileUrl, String kind) {
        byte[] bytes = download(name, fileUrl);
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return switch (kind) {
                case "docx" -> {
                    try (XWPFWordExtractor extractor = new XWPFWordExtractor(new XWPFDocument(in))) {
                        yield extractor.getText();
                    }
                }
                case "pptx" -> {
                    try (SlideShowExtractor<?, ?> extractor =
                                 new SlideShowExtractor<>(new XMLSlideShow(in))) {
                        yield extractor.getText();
                    }
                }
                default -> {
                    try (XSSFExcelExtractor extractor = new XSSFExcelExtractor(new XSSFWorkbook(in))) {
                        yield extractor.getText();
                    }
                }
            };
        } catch (IOException exception) {
            throw new BadRequestException("文档「" + name + "」无法解析,请确认文件未损坏");
        }
    }

    private byte[] download(String name, String fileUrl) {
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(fileUrl)).timeout(DOWNLOAD_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("下载课程资料失败: HTTP " + response.statusCode());
            }
            byte[] bytes = response.body();
            if (bytes.length > MAX_LOCAL_BYTES) {
                throw new BadRequestException(
                        "文档「" + name + "」超过 50MB,暂不支持本地解析入库");
            }
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException("下载课程资料失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("下载课程资料时线程被中断", exception);
        }
    }

    /** UTF-8 严格解码,失败回退 GBK(国内教学文档最常见的两种编码) */
    private static String decodeText(byte[] bytes) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    private static String suffixOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot == -1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
