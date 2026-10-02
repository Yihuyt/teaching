package cn.utcy.teaching.question.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("course_question")
public class CourseQuestion {

    @TableId
    private Long id;
    private Long courseId;
    private String title;
    /** 限时(分钟),null 为不限时 */
    private Integer timeLimitMinutes;
    private Boolean allowRetake;
    private Boolean revealAnswers;
    private Instant createdAt;
    private Instant updatedAt;

    protected CourseQuestion() {
    }

    public record Settings(Integer timeLimitMinutes, boolean allowRetake, boolean revealAnswers) {
    }

    public static CourseQuestion create(long courseId, String title, Settings settings) {
        CourseQuestion question = new CourseQuestion();
        question.courseId = courseId;
        question.title = title;
        question.apply(settings);
        question.createdAt = Instant.now();
        question.updatedAt = question.createdAt;
        return question;
    }

    public void update(String title, Settings settings) {
        this.title = title;
        apply(settings);
        this.updatedAt = Instant.now();
    }

    private void apply(Settings settings) {
        this.timeLimitMinutes = settings.timeLimitMinutes();
        this.allowRetake = settings.allowRetake();
        this.revealAnswers = settings.revealAnswers();
    }

    public Settings settings() {
        return new Settings(timeLimitMinutes, allowRetake, revealAnswers);
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public String getTitle() {
        return title;
    }

    public Integer getTimeLimitMinutes() {
        return timeLimitMinutes;
    }

    public Boolean getAllowRetake() {
        return allowRetake;
    }

    public Boolean getRevealAnswers() {
        return revealAnswers;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
