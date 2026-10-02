package cn.utcy.teaching.question.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.ai.document.DocumentParser;
import cn.utcy.teaching.ai.document.MineruClient;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService.MaterialView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 出题附件:
 * 教师上传到资料库的文件按 id 解析成纯文本,以「[附件文档]」块拼进用户消息;
 * 单个文件解析失败不中断,而是以「[文件:X —— 无法读取:原因]」一行告知模型与教师。
 * 资料元数据、下载地址与 MinerU 令牌都在请求线程解析(4xx 不进流);真正的文本抽取在 SSE 任务里做。
 */
@Component
public class QuestionAttachments {

    public static final int MAX_FILES = 5;
    static final int MAX_CHARS_PER_DOC = 200_000;
    static final int MAX_CHARS_TOTAL = 150_000;

    private final CourseMaterialApplicationService materials;
    private final DocumentParser parser;
    private final CourseAiKeys aiKeys;

    public QuestionAttachments(CourseMaterialApplicationService materials, DocumentParser parser,
                               CourseAiKeys aiKeys) {
        this.materials = materials;
        this.parser = parser;
        this.aiKeys = aiKeys;
    }

    /** 请求线程内解析好的附件:名称、可下载地址、PDF 所需的 MinerU 令牌(非 PDF 为 null) */
    public record Attachment(long materialId, String name, String fileUrl, String mineruToken) {
        boolean pdf() {
            return name.toLowerCase().endsWith(".pdf");
        }
    }

    public record Extracted(String documentsBlock, String summary) {
    }

    /**
     * 解析资料元数据(调用方已完成课程管理鉴权):必须是已上传完成的文件、类型可抽取;
     * PDF 要求课程负责人配置了 MinerU 令牌。
     */
    public List<Attachment> resolve(long courseId, List<Long> materialIds) {
        if (materialIds.size() > MAX_FILES) {
            throw new BadRequestException("一次最多附加 " + MAX_FILES + " 个文件");
        }
        List<Attachment> attachments = new ArrayList<>();
        for (Long materialId : materialIds) {
            attachments.add(resolveOne(courseId, materialId));
        }
        return attachments;
    }

    public Attachment resolveOne(long courseId, long materialId) {
        MaterialView material = materials.getTrusted(courseId, materialId);
        if (!DocumentParser.supported(material.name())) {
            throw new BadRequestException("资料「" + material.name()
                    + "」类型不支持解析,可用类型:pdf、docx、pptx、xlsx、md、txt");
        }
        String mineruToken = material.name().toLowerCase().endsWith(".pdf")
                ? aiKeys.mineruTokenForCourse(courseId)
                : null;
        // 下载票只对已上传完成的文件签发(文件夹 / 未完成上传 → 409)
        String fileUrl = materials.createDownloadTrusted(courseId, materialId).url();
        return new Attachment(materialId, material.name(), fileUrl, mineruToken);
    }

    static String summary(List<Attachment> attachments) {
        if (attachments.isEmpty()) {
            return "(无附件)";
        }
        StringBuilder lines = new StringBuilder();
        for (Attachment attachment : attachments) {
            if (!lines.isEmpty()) {
                lines.append('\n');
            }
            lines.append("- ").append(attachment.name()).append(" (")
                    .append(attachment.pdf() ? "pdf" : "document").append(')');
        }
        return lines.toString();
    }

    /**
     * 逐个抽取文本并渲染成「[附件文档]」块;单文件失败写成一行说明并继续,
     * 超出总配额的文件标记为跳过。
     */
    public Extracted extract(List<Attachment> attachments, Consumer<String> onProgress,
                             Consumer<String> onNotice, BooleanSupplier cancelled) {
        if (attachments.isEmpty()) {
            return new Extracted("", summary(attachments));
        }
        List<String> docs = new ArrayList<>();
        int totalChars = 0;
        for (Attachment attachment : attachments) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            onProgress.accept("正在解析附件「" + attachment.name() + "」…");
            String text;
            try {
                text = parser.parse(attachment.name(), attachment.fileUrl(), attachment.mineruToken(),
                        onProgress, cancelled);
            } catch (MineruClient.MineruUnavailableException | BadRequestException
                     | IllegalStateException exception) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                docs.add("[文件:" + attachment.name() + " —— 无法读取:" + exception.getMessage() + "]");
                onNotice.accept("附件「" + attachment.name() + "」无法读取:" + exception.getMessage()
                        + ";已跳过该文件继续出题。");
                continue;
            }
            if (text.isBlank()) {
                docs.add("[文件:" + attachment.name() + " —— 无法读取:没有可抽取的文本]");
                onNotice.accept("附件「" + attachment.name() + "」没有可抽取的文本;已跳过该文件继续出题。");
                continue;
            }
            int originalChars = text.length();
            if (text.length() > MAX_CHARS_PER_DOC) {
                text = text.substring(0, MAX_CHARS_PER_DOC)
                        + "... (已截断,原文共 " + originalChars + " 字符)";
            }
            int remaining = MAX_CHARS_TOTAL - totalChars;
            if (remaining <= 0) {
                docs.add("[文件:" + attachment.name() + " —— 已跳过:附件文本总配额已用尽]");
                onNotice.accept("附件「" + attachment.name() + "」超出本次文本总配额,已跳过。");
                continue;
            }
            if (text.length() > remaining) {
                text = text.substring(0, remaining)
                        + "... (已截断,原文共 " + originalChars + " 字符;本次总配额已用尽)";
            }
            totalChars += text.length();
            docs.add("[文件:" + attachment.name() + "]\n" + text);
        }
        return new Extracted("[附件文档]\n" + String.join("\n\n", docs), summary(attachments));
    }
}
