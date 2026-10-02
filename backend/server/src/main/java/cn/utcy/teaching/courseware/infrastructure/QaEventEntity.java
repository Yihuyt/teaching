package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("courseware_qa_event")
public class QaEventEntity {

    @TableId
    private Long id;
    private Long coursewareId;
    private Long accountId;
    private String sceneId;
    private String question;
    private String answer;
    private LocalDateTime askedAt;

    protected QaEventEntity() {
    }

    public QaEventEntity(long coursewareId, long accountId, String sceneId, String question,
                         String answer, LocalDateTime askedAt) {
        this.coursewareId = coursewareId;
        this.accountId = accountId;
        this.sceneId = sceneId;
        this.question = question;
        this.answer = answer;
        this.askedAt = askedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCoursewareId() {
        return coursewareId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getSceneId() {
        return sceneId;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public LocalDateTime getAskedAt() {
        return askedAt;
    }
}
