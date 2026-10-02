package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 测验作答记录(服务端判分产物,chosen 为选项 label 的 JSON 数组) */
@TableName("courseware_quiz_attempt")
public class QuizAttemptEntity {

    @TableId
    private Long id;
    private Long coursewareId;
    private Long accountId;
    private String sceneId;
    private String blockId;
    private String chosen;
    private Boolean correct;
    private LocalDateTime attemptedAt;

    protected QuizAttemptEntity() {
    }

    public QuizAttemptEntity(long coursewareId, long accountId, String sceneId, String blockId,
                             String chosen, boolean correct, LocalDateTime attemptedAt) {
        this.coursewareId = coursewareId;
        this.accountId = accountId;
        this.sceneId = sceneId;
        this.blockId = blockId;
        this.chosen = chosen;
        this.correct = correct;
        this.attemptedAt = attemptedAt;
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

    public String getBlockId() {
        return blockId;
    }

    public String getChosen() {
        return chosen;
    }

    public Boolean getCorrect() {
        return correct;
    }

    public LocalDateTime getAttemptedAt() {
        return attemptedAt;
    }
}
