package cn.utcy.teaching.tutor.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("tutor_assistant")
public class TutorAssistantEntity {

    @TableId
    private Long id;
    private Long courseId;
    private String name;
    private String description;
    private String instructions;
    private String model;
    private Double temperature;
    private Boolean reasoning;
    private Integer maxRounds;
    private Boolean visibleToStudents;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected TutorAssistantEntity() {
    }

    public TutorAssistantEntity(long courseId, String name, String description, String instructions,
                                ModelSettings model, boolean visibleToStudents, LocalDateTime now) {
        this.courseId = courseId;
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        applyModel(model);
        this.visibleToStudents = visibleToStudents;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public record ModelSettings(String model, double temperature, boolean reasoning, int maxRounds) {
    }

    public void update(String name, String description, String instructions, ModelSettings model,
                       boolean visibleToStudents, LocalDateTime now) {
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        applyModel(model);
        this.visibleToStudents = visibleToStudents;
        this.updatedAt = now;
    }

    private void applyModel(ModelSettings settings) {
        this.model = settings.model();
        this.temperature = settings.temperature();
        this.reasoning = settings.reasoning();
        this.maxRounds = settings.maxRounds();
    }

    public ModelSettings modelSettings() {
        return new ModelSettings(model, temperature, reasoning, maxRounds);
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getInstructions() {
        return instructions;
    }

    public Boolean getVisibleToStudents() {
        return visibleToStudents;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
