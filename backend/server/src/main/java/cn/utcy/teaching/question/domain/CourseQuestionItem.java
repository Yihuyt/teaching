package cn.utcy.teaching.question.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 试题里的一道题:题型 / 题干 / 选项 / 标准答案 / 解析 / 分值;options_json 与 answer_json 的形态由题型契约约束 */
@TableName("course_question_item")
public class CourseQuestionItem {

    @TableId
    private Long id;
    private Long questionId;
    private Integer position;
    private CourseQuestionType type;
    private String stemMarkdown;
    private String optionsJson;
    private String answerJson;
    private String analysisMarkdown;
    private Double score;

    protected CourseQuestionItem() {
    }

    public CourseQuestionItem(long questionId, int position, CourseQuestionType type, String stemMarkdown,
                              String optionsJson, String answerJson, String analysisMarkdown, double score) {
        this.questionId = questionId;
        this.position = position;
        this.type = type;
        this.stemMarkdown = stemMarkdown;
        this.optionsJson = optionsJson;
        this.answerJson = answerJson;
        this.analysisMarkdown = analysisMarkdown;
        this.score = score;
    }

    public Long getId() {
        return id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public Integer getPosition() {
        return position;
    }

    public CourseQuestionType getType() {
        return type;
    }

    public String getStemMarkdown() {
        return stemMarkdown;
    }

    public String getOptionsJson() {
        return optionsJson;
    }

    public String getAnswerJson() {
        return answerJson;
    }

    public String getAnalysisMarkdown() {
        return analysisMarkdown;
    }

    public Double getScore() {
        return score;
    }
}
