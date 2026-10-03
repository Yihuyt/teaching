package cn.utcy.teaching.blockcoding.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("course_blockcoding_config")
public class CourseBlockCodingConfig {
    @TableId(value = "course_id", type = IdType.INPUT)
    private Long courseId;
    private Boolean enabled;
    private String tutorPrompt;
    private String model;
    private Instant createdAt;
    private Instant updatedAt;

    protected CourseBlockCodingConfig() {
    }

    public static CourseBlockCodingConfig create(long courseId, boolean enabled, String tutorPrompt, String model) {
        CourseBlockCodingConfig config = new CourseBlockCodingConfig();
        Instant now = Instant.now();
        config.courseId = courseId;
        config.enabled = enabled;
        config.tutorPrompt = tutorPrompt;
        config.model = model;
        config.createdAt = now;
        config.updatedAt = now;
        return config;
    }

    public void update(boolean enabled, String tutorPrompt, String model) {
        this.enabled = enabled;
        this.tutorPrompt = tutorPrompt;
        this.model = model;
        this.updatedAt = Instant.now();
    }

    public Long getCourseId() {
        return courseId;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public String getTutorPrompt() {
        return tutorPrompt;
    }

    public String getModel() {
        return model;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
