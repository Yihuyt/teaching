package cn.utcy.teaching.course.domain;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import java.time.Instant;
import java.util.Objects;

@TableName("course_outline_item")
public class CourseOutlineItem {

    @TableId
    private Long id;
    private Long courseId;
    // 移到顶层时 unit_id 必须真正写回 NULL(默认策略跳过 null,会撞 unit_scope 的 CHECK)
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long unitId;
    /** = COALESCE(unit_id, 0):顶层内容(不属于单元)记 0,配合排序唯一键 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long unitScope;
    private CourseOutlineItemType itemType;
    private Long materialId;
    private Long questionId;
    private Long programmingProblemId;
    private int position;
    private Instant createdAt;

    protected CourseOutlineItem() {
    }

    public CourseOutlineItem(
            Long id,
            Long courseId,
            Long unitId,
            CourseOutlineItemType itemType,
            Long materialId,
            Long questionId,
            Long programmingProblemId,
            int position,
            Instant createdAt
    ) {
        this.id = id;
        this.courseId = courseId;
        this.unitId = unitId;
        this.unitScope = unitId == null ? 0L : unitId;
        this.itemType = itemType;
        this.materialId = materialId;
        this.questionId = questionId;
        this.programmingProblemId = programmingProblemId;
        this.position = position;
        this.createdAt = createdAt;
        requireValidSource();
    }

    public static CourseOutlineItem create(
            long courseId,
            Long unitId,
            CourseOutlineItemType itemType,
            long contentId,
            int position
    ) {
        Objects.requireNonNull(itemType, "课程内容类型不能为空");
        return new CourseOutlineItem(
                null,
                courseId,
                unitId,
                itemType,
                itemType == CourseOutlineItemType.MATERIAL ? contentId : null,
                itemType == CourseOutlineItemType.QUESTION ? contentId : null,
                itemType == CourseOutlineItemType.PROGRAMMING_PROBLEM ? contentId : null,
                position,
                Instant.now());
    }

    /** 类型 → 内容列:整个模块唯一的一处映射 */
    public static SFunction<CourseOutlineItem, Long> contentColumn(CourseOutlineItemType itemType) {
        return switch (itemType) {
            case MATERIAL -> CourseOutlineItem::getMaterialId;
            case QUESTION -> CourseOutlineItem::getQuestionId;
            case PROGRAMMING_PROBLEM -> CourseOutlineItem::getProgrammingProblemId;
        };
    }

    public void moveTo(Long unitId, int position) {
        this.unitId = unitId;
        this.unitScope = unitId == null ? 0L : unitId;
        this.position = position;
    }

    public long contentId() {
        requireValidSource();
        return contentColumn(itemType).apply(this);
    }

    private void requireValidSource() {
        Objects.requireNonNull(itemType, "课程内容类型不能为空");
        long populatedSources = java.util.stream.Stream.of(
                        materialId,
                        questionId,
                        programmingProblemId)
                .filter(Objects::nonNull)
                .count();
        if (populatedSources != 1) {
            throw new IllegalArgumentException("课程内容必须且只能关联一项内容");
        }
        if (contentColumn(itemType).apply(this) == null) {
            throw new IllegalArgumentException("课程内容类型与内容字段不一致");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getUnitId() {
        return unitId;
    }

    public CourseOutlineItemType getItemType() {
        return itemType;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public Long getProgrammingProblemId() {
        return programmingProblemId;
    }

    public int getPosition() {
        return position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
