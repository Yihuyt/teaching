package cn.utcy.teaching.programming.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("programming_problem_sample")
public class ProgrammingProblemSample {

    @TableId
    private Long id;
    private Long problemId;
    private int position;
    private String inputText;
    private String outputText;

    protected ProgrammingProblemSample() {
    }

    public ProgrammingProblemSample(
            Long id,
            Long problemId,
            int position,
            String inputText,
            String outputText
    ) {
        this.id = id;
        this.problemId = problemId;
        this.position = position;
        this.inputText = inputText;
        this.outputText = outputText;
    }

    public static ProgrammingProblemSample create(
            long problemId,
            int position,
            String inputText,
            String outputText
    ) {
        return new ProgrammingProblemSample(
                null, problemId, position, inputText, outputText);
    }

    public Long getId() {
        return id;
    }

    public Long getProblemId() {
        return problemId;
    }

    public int getPosition() {
        return position;
    }

    public String getInputText() {
        return inputText;
    }

    public String getOutputText() {
        return outputText;
    }
}
