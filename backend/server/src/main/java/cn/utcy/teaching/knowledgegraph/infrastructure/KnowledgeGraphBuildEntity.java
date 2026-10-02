package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.knowledgegraph.domain.BuildStatus;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 教材构建任务:解析产物与教师确认稿的持久化载体。运行态(parsing / extracting)的行由直启管线执行:
 * run_token 是本次运行的写围栏(每次启动换新、收尾清空,旧线程迟到的写入一律不生效),
 * progress_heartbeat_at 由进展心跳更新——停更超时即视为中断(进程消失或挂死),由判滞巡检判失败;
 * 断点数据(阶段产物、小节、MinerU 台账)保留,教师可重试续跑。
 */
@TableName("knowledge_graph_build")
public class KnowledgeGraphBuildEntity {

    @TableId
    private Long id;
    private Long courseId;
    private Long materialId;
    private String materialName;
    private BuildStatus status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String runToken;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime progressHeartbeatAt;
    private boolean cancelRequested;
    private String pageMarkdownJson;
    private String tocDraftJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String tocConfirmedJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String previewJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String mineruTasksJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected KnowledgeGraphBuildEntity() {
    }

    public KnowledgeGraphBuildEntity(long courseId, long materialId, String materialName,
                                     long createdBy, String runToken, LocalDateTime now) {
        this.courseId = courseId;
        this.materialId = materialId;
        this.materialName = materialName;
        this.createdBy = createdBy;
        this.createdAt = now;
        begin(BuildStatus.PARSING, runToken, now);
    }

    // ---- 运行 --------------------------------------------------------------------

    private void begin(BuildStatus target, String runToken, LocalDateTime now) {
        this.status = target;
        this.runToken = runToken;
        this.progressHeartbeatAt = now;
        this.cancelRequested = false;
        this.errorMessage = null;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == BuildStatus.PARSING || status == BuildStatus.EXTRACTING;
    }

    public void requestCancel(LocalDateTime now) {
        this.cancelRequested = true;
        this.updatedAt = now;
    }

    public void rememberMineruTasks(String mineruTasksJson, LocalDateTime now) {
        this.mineruTasksJson = mineruTasksJson;
        this.updatedAt = now;
    }

    /** 离开运行态:run 标记与心跳清空,迟到的旧线程从此写不进任何东西 */
    private void settle(BuildStatus target, LocalDateTime now) {
        this.status = target;
        this.runToken = null;
        this.progressHeartbeatAt = null;
        this.cancelRequested = false;
        this.updatedAt = now;
    }

    // ---- 状态迁移 ------------------------------------------------------------------

    public void tocReady(String pageMarkdownJson, String tocDraftJson, LocalDateTime now) {
        this.pageMarkdownJson = pageMarkdownJson;
        this.tocDraftJson = tocDraftJson;
        this.mineruTasksJson = null;
        this.errorMessage = null;
        settle(BuildStatus.TOC_READY, now);
    }

    public void confirmToc(String tocConfirmedJson, LocalDateTime now) {
        this.tocConfirmedJson = tocConfirmedJson;
        this.previewJson = null;
        this.updatedAt = now;
    }

    public void extracting(String runToken, LocalDateTime now) {
        begin(BuildStatus.EXTRACTING, runToken, now);
    }

    public void extracted(String previewJson, LocalDateTime now) {
        this.previewJson = previewJson;
        this.errorMessage = null;
        settle(BuildStatus.EXTRACTED, now);
    }

    public void failed(String message, LocalDateTime now) {
        this.errorMessage = Text.truncate(message, 1000);
        settle(BuildStatus.FAILED, now);
    }

    /** 重新解析:回到解析态,目录确认稿、抽取小节与预览作废(页码会变);MinerU 任务登记保留,同一文件的云端结果可直接续接 */
    public void reparse(String runToken, LocalDateTime now) {
        this.tocConfirmedJson = null;
        this.previewJson = null;
        begin(BuildStatus.PARSING, runToken, now);
    }

    /** 目录改动后回到已出目录:预览作废由 confirmToc 完成,这里只换状态 */
    public void backToTocReady(LocalDateTime now) {
        this.errorMessage = null;
        settle(BuildStatus.TOC_READY, now);
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public String getMaterialName() {
        return materialName;
    }

    public BuildStatus getStatus() {
        return status;
    }

    public String getRunToken() {
        return runToken;
    }

    public LocalDateTime getProgressHeartbeatAt() {
        return progressHeartbeatAt;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public String getPageMarkdownJson() {
        return pageMarkdownJson;
    }

    public String getTocDraftJson() {
        return tocDraftJson;
    }

    public String getTocConfirmedJson() {
        return tocConfirmedJson;
    }

    public String getPreviewJson() {
        return previewJson;
    }

    public String getMineruTasksJson() {
        return mineruTasksJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
