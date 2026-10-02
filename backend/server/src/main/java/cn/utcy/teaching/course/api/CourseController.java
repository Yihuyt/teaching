package cn.utcy.teaching.course.api;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.web.PageResponse;
import cn.utcy.teaching.course.application.CourseApplicationService;
import cn.utcy.teaching.course.application.CourseApplicationService.CourseManagementView;
import cn.utcy.teaching.course.application.CourseApplicationService.CourseView;
import cn.utcy.teaching.course.application.CourseLibraryDeletionImpactService;
import cn.utcy.teaching.course.application.CourseLibraryDeletionImpactService.DeletionImpactView;
import cn.utcy.teaching.course.application.CourseMemberApplicationService;
import cn.utcy.teaching.course.application.CourseMemberApplicationService.MemberView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.UnitPlacement;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineUnitView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineItemView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.CourseOutlineView;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.ItemPlacement;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CourseApplicationService courses;
    private final CourseMemberApplicationService members;
    private final CourseOutlineApplicationService outline;
    private final CourseLibraryDeletionImpactService deletionImpact;

    public CourseController(
            CourseApplicationService courses,
            CourseMemberApplicationService members,
            CourseOutlineApplicationService outline,
            CourseLibraryDeletionImpactService deletionImpact
    ) {
        this.courses = courses;
        this.members = members;
        this.outline = outline;
        this.deletionImpact = deletionImpact;
    }

    @GetMapping
    public PageResponse<CourseView> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean management
    ) {
        return courses.list(page, size, keyword, management);
    }

    @GetMapping("/{courseId}")
    public CourseView get(@PathVariable long courseId) {
        return courses.get(courseId);
    }

    @GetMapping("/{courseId}/management")
    public CourseManagementView getForManagement(@PathVariable long courseId) {
        return courses.getForManagement(courseId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseView create(@Valid @RequestBody SaveCourseRequest request) {
        return courses.create(request.title(), request.descriptionMarkdown());
    }

    @PostMapping("/join")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseView join(@Valid @RequestBody JoinCourseRequest request) {
        return courses.join(request.joinCode());
    }

    @PutMapping("/{courseId}")
    public CourseView update(
            @PathVariable long courseId,
            @Valid @RequestBody SaveCourseRequest request
    ) {
        return courses.update(courseId, request.title(), request.descriptionMarkdown());
    }

    @PostMapping("/{courseId}/library/deletion-impact")
    public DeletionImpactView libraryDeletionImpact(
            @PathVariable long courseId,
            @Valid @RequestBody DeletionImpactRequest request
    ) {
        return deletionImpact.impact(courseId, request.materialIds(), request.questionIds(), request.problemIds());
    }

    /** 发布:学生可凭课程码加入、成员可进入学习 */
    @PostMapping("/{courseId}/publication")
    public CourseView publish(@PathVariable long courseId) {
        return courses.publish(courseId);
    }

    /** 取消发布:学生不能加入、成员不能进入;可再次发布 */
    @DeleteMapping("/{courseId}/publication")
    public CourseView unpublish(@PathVariable long courseId) {
        return courses.unpublish(courseId);
    }

    @PutMapping("/{courseId}/join-code")
    public CourseManagementView rotateJoinCode(@PathVariable long courseId) {
        return courses.rotateJoinCode(courseId);
    }

    @DeleteMapping("/{courseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long courseId) {
        courses.delete(courseId);
    }

    @GetMapping("/{courseId}/outline")
    public CourseOutlineView outline(@PathVariable long courseId) {
        return outline.get(courseId);
    }

    @GetMapping("/{courseId}/management/outline")
    public CourseOutlineView outlineForManagement(@PathVariable long courseId) {
        return outline.getForManagement(courseId);
    }

    @PostMapping("/{courseId}/units")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseOutlineUnitView createUnit(
            @PathVariable long courseId,
            @Valid @RequestBody CreateUnitRequest request
    ) {
        return outline.createUnit(
                courseId,
                request.parentId(),
                request.title());
    }

    @PutMapping("/{courseId}/units/{unitId}")
    public CourseOutlineUnitView updateUnit(
            @PathVariable long courseId,
            @PathVariable long unitId,
            @Valid @RequestBody UpdateUnitRequest request
    ) {
        return outline.updateUnit(
                courseId,
                unitId,
                request.title());
    }

    @DeleteMapping("/{courseId}/units/{unitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUnit(@PathVariable long courseId, @PathVariable long unitId) {
        outline.deleteUnit(courseId, unitId);
    }

    @GetMapping("/{courseId}/members")
    public List<MemberView> members(@PathVariable long courseId) {
        return members.list(courseId);
    }

    @DeleteMapping("/{courseId}/members/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable long courseId, @PathVariable long accountId) {
        members.remove(courseId, accountId);
    }

    @PostMapping("/{courseId}/outline-items")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseOutlineItemView addOutlineItem(
            @PathVariable long courseId,
            @Valid @RequestBody AddOutlineItemRequest request
    ) {
        return outline.addItem(
                courseId,
                request.unitId(),
                request.itemType(),
                request.contentId());
    }

    @DeleteMapping("/{courseId}/outline-items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeOutlineItem(@PathVariable long courseId, @PathVariable long itemId) {
        outline.removeItem(courseId, itemId);
    }

    @PutMapping("/{courseId}/outline-order")
    public CourseOutlineView replaceOutlineOrder(
            @PathVariable long courseId,
            @Valid @RequestBody ReplaceOutlineOrderRequest request
    ) {
        return outline.replaceOrder(
                courseId,
                request.units().stream()
                        .map(item -> new UnitPlacement(
                                item.unitId(),
                                item.parentId(),
                                item.position()))
                        .toList(),
                request.items().stream()
                        .map(item -> new ItemPlacement(
                                item.itemId(),
                                item.unitId(),
                                item.position()))
                        .toList());
    }

    public record SaveCourseRequest(
            @NotBlank(message = "课程名称不能为空")
            @Size(max = 128, message = "课程名称不能超过 128 个字符")
            String title,
            @NotNull(message = "课程说明不能为空") String descriptionMarkdown
    ) {
    }

    public record CreateUnitRequest(
            @Schema(nullable = true) @Min(value = 1, message = "父单元编号必须大于等于 1") Long parentId,
            @NotBlank(message = "单元名称不能为空")
            @Size(max = 128, message = "单元名称不能超过 128 个字符")
            String title
    ) {
    }

    public record UpdateUnitRequest(
            @NotBlank(message = "单元名称不能为空")
            @Size(max = 128, message = "单元名称不能超过 128 个字符")
            String title
    ) {
    }

    public record DeletionImpactRequest(
            @NotNull List<@Min(1) Long> materialIds,
            @NotNull List<@Min(1) Long> questionIds,
            @NotNull List<@Min(1) Long> problemIds
    ) {
    }

    public record JoinCourseRequest(
            @NotBlank(message = "课程码不能为空")
            @Size(max = 32, message = "课程码格式不正确")
            String joinCode
    ) {
    }

    public record AddOutlineItemRequest(
            @Min(value = 1, message = "单元编号必须大于等于 1")
            @Schema(nullable = true)
            Long unitId,
            @NotNull(message = "课程内容类型不能为空") CourseOutlineItemType itemType,
            @NotNull(message = "来源内容编号不能为空")
            @Min(value = 1, message = "来源内容编号必须大于等于 1")
            Long contentId
    ) {
    }

    public record ReplaceOutlineOrderRequest(
            @NotNull(message = "单元排序不能为空")
            @Valid
            List<UnitOrderRequest> units,
            @NotNull(message = "内容排序不能为空")
            @Valid
            List<ItemOrderRequest> items
    ) {
    }

    public record UnitOrderRequest(
            @Min(value = 1, message = "单元编号必须大于等于 1") long unitId,
            @Schema(nullable = true) @Min(value = 1, message = "父单元编号必须大于等于 1") Long parentId,
            @Min(value = 1, message = "单元顺序必须大于等于 1") int position
    ) {
    }

    public record ItemOrderRequest(
            @Min(value = 1, message = "课程内容编号必须大于等于 1") long itemId,
            @Min(value = 1, message = "单元编号必须大于等于 1") @Schema(nullable = true) Long unitId,
            @Min(value = 1, message = "内容顺序必须大于等于 1") int position
    ) {
    }
}
