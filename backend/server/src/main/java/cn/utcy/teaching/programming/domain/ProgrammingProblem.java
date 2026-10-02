package cn.utcy.teaching.programming.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("programming_problem")
public class ProgrammingProblem {
    @TableId
    private Long id;
    private Long ownerId;
    private Long courseId;
    private String title;
    private String statementMarkdown;
    private ProblemDifficulty difficulty;
    private int timeLimitMs;
    private int memoryLimitMb;
    private int outputLimitKb;
    private String languagesJson;
    private String testcaseSha256;
    private Long testcaseSizeBytes;
    private Instant testcaseConfirmedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected ProgrammingProblem() {
    }

    public ProgrammingProblem(Long id, Long ownerId, Long courseId,
                              String title, String statementMarkdown,
                              ProblemDifficulty difficulty, int timeLimitMs, int memoryLimitMb,
                              int outputLimitKb, String languagesJson, String testcaseSha256,
                              Long testcaseSizeBytes, Instant testcaseConfirmedAt,
                              Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.courseId = courseId;
        this.title = title;
        this.statementMarkdown = statementMarkdown;
        this.difficulty = difficulty;
        this.timeLimitMs = timeLimitMs;
        this.memoryLimitMb = memoryLimitMb;
        this.outputLimitKb = outputLimitKb;
        this.languagesJson = languagesJson;
        this.testcaseSha256 = testcaseSha256;
        this.testcaseSizeBytes = testcaseSizeBytes;
        this.testcaseConfirmedAt = testcaseConfirmedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ProgrammingProblem create(
            long ownerId,
            long courseId,
            String title,
            String statementMarkdown,
            ProblemDifficulty difficulty,
            int timeLimitMs,
            int memoryLimitMb,
            int outputLimitKb,
            String languagesJson
    ) {
        Instant now = Instant.now();
        return new ProgrammingProblem(null, ownerId, courseId, title, statementMarkdown, difficulty,
                timeLimitMs, memoryLimitMb, outputLimitKb, languagesJson, null, null, null, now, now);
    }

    public void update(String title, String statementMarkdown, ProblemDifficulty difficulty,
                       int timeLimitMs, int memoryLimitMb, int outputLimitKb, String languagesJson) {
        this.title = title;
        this.statementMarkdown = statementMarkdown;
        this.difficulty = difficulty;
        this.timeLimitMs = timeLimitMs;
        this.memoryLimitMb = memoryLimitMb;
        this.outputLimitKb = outputLimitKb;
        this.languagesJson = languagesJson;
        this.updatedAt = Instant.now();
    }

    public void confirmTestcase(String sha256, long sizeBytes) {
        testcaseSha256 = sha256;
        testcaseSizeBytes = sizeBytes;
        testcaseConfirmedAt = Instant.now();
        updatedAt = testcaseConfirmedAt;
    }

    public void clearTestcase() {
        testcaseSha256 = null;
        testcaseSizeBytes = null;
        testcaseConfirmedAt = null;
        updatedAt = Instant.now();
    }

    public boolean hasConfirmedTestcase() {
        return testcaseSha256 != null
                && testcaseSizeBytes != null
                && testcaseConfirmedAt != null;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public Long getCourseId() {
        return courseId;
    }

    public String getTitle() {
        return title;
    }

    public String getStatementMarkdown() {
        return statementMarkdown;
    }

    public ProblemDifficulty getDifficulty() {
        return difficulty;
    }

    public int getTimeLimitMs() {
        return timeLimitMs;
    }

    public int getMemoryLimitMb() {
        return memoryLimitMb;
    }

    public int getOutputLimitKb() {
        return outputLimitKb;
    }

    public String getLanguagesJson() {
        return languagesJson;
    }

    public String getTestcaseSha256() {
        return testcaseSha256;
    }

    public Long getTestcaseSizeBytes() {
        return testcaseSizeBytes;
    }

    public Instant getTestcaseConfirmedAt() {
        return testcaseConfirmedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
