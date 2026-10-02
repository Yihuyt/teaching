package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.knowledgegraph.domain.SectionStatus;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 抽取子片:失败恢复的最小重试单元;summary/result 为该片的 LLM 产物 JSON */
@TableName("knowledge_graph_build_section")
public class BuildSectionEntity {

    @TableId
    private Long id;
    private Long buildId;
    private Integer sectionIndex;
    /** 对应目录确认稿的条目下标(切片与目录的稳定关联) */
    private Integer entryIndex;
    private String number;
    private String title;
    private String path;
    private Integer startPage;
    private Integer endPage;
    private SectionStatus status;
    private String summaryJson;
    private String resultJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    protected BuildSectionEntity() {
    }

    public BuildSectionEntity(long buildId, int sectionIndex, int entryIndex, String number,
                              String title, String path, int startPage, int endPage) {
        this.buildId = buildId;
        this.sectionIndex = sectionIndex;
        this.entryIndex = entryIndex;
        this.number = number;
        this.title = title;
        this.path = path;
        this.startPage = startPage;
        this.endPage = endPage;
        this.status = SectionStatus.PENDING;
    }

    public void running() {
        this.status = SectionStatus.RUNNING;
        this.errorMessage = null;
    }

    /** 构建被判中断收尾时,上次在途的小节回到待抽取(界面不留假的「抽取中」) */
    public void resetToPending() {
        this.status = SectionStatus.PENDING;
        this.errorMessage = null;
    }

    public void done(String summaryJson, String resultJson) {
        this.status = SectionStatus.DONE;
        this.summaryJson = summaryJson;
        this.resultJson = resultJson;
        this.errorMessage = null;
    }

    public void failed(String message) {
        this.status = SectionStatus.FAILED;
        this.errorMessage = Text.truncate(message, 1000);
    }

    /** 保留失败原因:界面上「已忽略」仍能看到当初为什么失败 */
    public void ignored() {
        this.status = SectionStatus.IGNORED;
    }

    public Long getId() {
        return id;
    }

    public Long getBuildId() {
        return buildId;
    }

    public Integer getSectionIndex() {
        return sectionIndex;
    }

    public Integer getEntryIndex() {
        return entryIndex;
    }

    public String getNumber() {
        return number;
    }

    public String getTitle() {
        return title;
    }

    public String getPath() {
        return path;
    }

    public Integer getStartPage() {
        return startPage;
    }

    public Integer getEndPage() {
        return endPage;
    }

    public SectionStatus getStatus() {
        return status;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
