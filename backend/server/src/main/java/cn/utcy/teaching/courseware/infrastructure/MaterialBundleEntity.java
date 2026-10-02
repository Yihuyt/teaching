package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 课件素材包:附加到某个课件工作台的一组资料解析合并后的文本与图片清单
 * (图片对象在 OSS courseware/bundles/{id}/)。随课件删除;教师可在工作台移除。
 * state:parsing(行已建,解析进行中)→ ready(解析合并落库完成)。进程崩溃留下的 parsing 行
 * 不进任何清单与读取,由孤儿清理器按年龄删除——没有状态列时它会被当成一份空素材喂给大纲生成。
 */
@TableName("courseware_material_bundle")
public class MaterialBundleEntity {

    @TableId
    private Long id;
    private Long courseId;
    private Long coursewareId;
    private Long accountId;
    private String name;
    private String state;
    private String text;
    private String imagesJson;
    private String sourcesJson;
    private LocalDateTime createdAt;

    protected MaterialBundleEntity() {
    }

    public static final String STATE_PARSING = "parsing";
    public static final String STATE_READY = "ready";

    public MaterialBundleEntity(long courseId, long coursewareId, long accountId, String name, String text,
                                String imagesJson, String sourcesJson, LocalDateTime createdAt) {
        this.courseId = courseId;
        this.coursewareId = coursewareId;
        this.accountId = accountId;
        this.name = name;
        this.state = STATE_PARSING;
        this.text = text;
        this.imagesJson = imagesJson;
        this.sourcesJson = sourcesJson;
        this.createdAt = createdAt;
    }

    public String getState() {
        return state;
    }

    public void ready() {
        this.state = STATE_READY;
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getCoursewareId() {
        return coursewareId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getName() {
        return name;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getImagesJson() {
        return imagesJson;
    }

    public void setImagesJson(String imagesJson) {
        this.imagesJson = imagesJson;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public void setSourcesJson(String sourcesJson) {
        this.sourcesJson = sourcesJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
