package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseOutlineSourceRegistry.ContentKey;
import cn.utcy.teaching.course.domain.CourseUnit;
import cn.utcy.teaching.course.domain.CourseOutlineItem;
import cn.utcy.teaching.course.infrastructure.CourseUnitMapper;
import cn.utcy.teaching.course.infrastructure.CourseOutlineItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 课程内容:单元树 + 单元内的内容项。内容项只引用就绪的内容(由各类型的提供者在加入时校验),
 * 因此教师端与学生端看到的是同一份视图。
 */
@Service
public class CourseOutlineApplicationService implements CourseOutlineLinks {

    private final CourseApplicationService courses;
    private final CourseUnitMapper units;
    private final CourseOutlineItemMapper outlineItems;
    private final CurrentActor currentActor;
    private final CourseOutlineSourceRegistry sources;

    public CourseOutlineApplicationService(
            CourseApplicationService courses,
            CourseUnitMapper units,
            CourseOutlineItemMapper outlineItems,
            CurrentActor currentActor,
            CourseOutlineSourceRegistry sources
    ) {
        this.courses = courses;
        this.units = units;
        this.outlineItems = outlineItems;
        this.currentActor = currentActor;
        this.sources = sources;
    }

    @Transactional(readOnly = true)
    public CourseOutlineView get(long courseId) {
        courses.requireLearningAccess(courseId, currentActor.require());
        return buildOutline(courseId);
    }

    @Transactional(readOnly = true)
    public CourseOutlineView getForManagement(long courseId) {
        courses.requireManagementAccess(courseId, currentActor.require());
        return buildOutline(courseId);
    }

    /** 无门禁读取(学情等服务端消费;调用方自行鉴权) */
    @Transactional(readOnly = true)
    public CourseOutlineView getTrusted(long courseId) {
        return buildOutline(courseId);
    }

    /** unitId 为空 = 顶层内容(不属于任何单元) */
    @Transactional
    public CourseOutlineItemView addItem(
            long courseId,
            Long unitId,
            CourseOutlineItemType itemType,
            long contentId
    ) {
        courses.requireManageableForUpdate(courseId, currentActor.require());
        if (unitId != null) {
            requireUnit(courseId, unitId);
        }
        String title = sources.provider(itemType).requireLinkable(courseId, contentId);
        List<CourseOutlineItem> siblings = itemsInUnit(courseId, unitId);
        CourseOutlineItem item = CourseOutlineItem.create(
                courseId, unitId, itemType, contentId, siblings.size() + 1);
        courses.requireMutation(outlineItems.insert(item), "课程内容添加未生效");
        return itemView(item, title);
    }

    @Transactional
    public void removeItem(long courseId, long itemId) {
        courses.requireManageableForUpdate(courseId, currentActor.require());
        CourseOutlineItem item = requireOutlineItem(courseId, itemId);
        courses.requireMutation(outlineItems.deleteById(itemId), "课程内容状态已变化，移除未生效");
        closeItemGap(courseId, item.getUnitId(), item.getPosition());
    }

    @Transactional
    public CourseOutlineUnitView createUnit(long courseId, Long parentId, String title) {
        courses.requireManageableForUpdate(courseId, currentActor.require());
        if (parentId != null) {
            requireUnit(courseId, parentId);
        }
        List<CourseUnit> siblings = childUnits(courseId, parentId);
        CourseUnit unit = CourseUnit.create(courseId, parentId, title.trim(), siblings.size() + 1);
        courses.requireMutation(units.insert(unit), "单元创建未生效");
        return unitView(unit, List.of(), List.of());
    }

    @Transactional
    public CourseOutlineUnitView updateUnit(long courseId, long unitId, String title) {
        courses.requireManageableForUpdate(courseId, currentActor.require());
        CourseUnit unit = requireUnit(courseId, unitId);
        unit.update(title.trim());
        courses.requireMutation(units.updateById(unit), "单元状态已变化，更新未生效");
        return unitView(unit, List.of(), List.of());
    }

    /** 删单元连同其子单元与其中的课程内容条目(条目只是引用,资料库里的内容不动) */
    @Transactional
    public void deleteUnit(long courseId, long unitId) {
        courses.requireManageableForUpdate(courseId, currentActor.require());
        CourseUnit unit = requireUnit(courseId, unitId);
        deleteUnitTree(courseId, unitId);
        closeUnitGap(courseId, unit.getParentId(), unit.getPosition());
    }

    private void deleteUnitTree(long courseId, long unitId) {
        for (CourseUnit child : childUnits(courseId, unitId)) {
            deleteUnitTree(courseId, child.getId());
        }
        outlineItems.delete(new LambdaQueryWrapper<CourseOutlineItem>()
                .eq(CourseOutlineItem::getCourseId, courseId)
                .eq(CourseOutlineItem::getUnitId, unitId));
        courses.requireMutation(units.deleteById(unitId), "单元状态已变化，删除未生效");
    }

    @Transactional
    public CourseOutlineView replaceOrder(
            long courseId,
            List<UnitPlacement> requestedUnits,
            List<ItemPlacement> requestedItems
    ) {
        courses.requireManageableForUpdate(courseId, currentActor.require());
        List<CourseUnit> storedUnits = courseUnits(courseId);
        List<CourseOutlineItem> storedItems = courseItems(courseId);
        validateReplacement(storedUnits, storedItems, requestedUnits, requestedItems);
        stageUnits(storedUnits);
        Map<Long, CourseUnit> unitsById = indexUnits(storedUnits);
        for (UnitPlacement placement : requestedUnits) {
            CourseUnit unit = unitsById.get(placement.unitId());
            unit.moveTo(placement.parentId(), placement.position());
            courses.requireMutation(units.updateById(unit), "单元排序期间状态已变化，保存未生效");
        }
        stageItems(storedItems);
        Map<Long, CourseOutlineItem> itemsById = indexItems(storedItems);
        for (ItemPlacement placement : requestedItems) {
            CourseOutlineItem item = itemsById.get(placement.itemId());
            item.moveTo(placement.unitId(), placement.position());
            courses.requireMutation(outlineItems.updateById(item), "课程内容排序期间状态已变化，保存未生效");
        }
        return buildOutline(courseId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isLinked(long courseId, CourseOutlineItemType itemType, long contentId) {
        return outlineItems.exists(contentQuery(courseId, itemType, contentId));
    }

    /** 内容删除时从课程内容移除其全部编排位置(调用方已锁内容行、已校验管理权限) */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void unlink(long courseId, CourseOutlineItemType itemType, long contentId) {
        for (CourseOutlineItem item : outlineItems.selectList(contentQuery(courseId, itemType, contentId))) {
            courses.requireMutation(outlineItems.deleteById(item.getId()), "课程内容状态已变化，移除未生效");
            closeItemGap(courseId, item.getUnitId(), item.getPosition());
        }
    }

    private CourseOutlineView buildOutline(long courseId) {
        List<CourseUnit> storedUnits = courseUnits(courseId);
        List<CourseOutlineItem> storedItems = courseItems(courseId);
        Map<ContentKey, String> titles = sources.requireTitles(courseId, storedItems);
        List<CourseOutlineItemView> topItems = new ArrayList<>();
        Map<Long, List<CourseOutlineItemView>> itemViewsByUnit = new HashMap<>();
        for (CourseOutlineItem item : storedItems) {
            String title = titles.get(new ContentKey(item.getItemType(), item.contentId()));
            if (item.getUnitId() == null) {
                topItems.add(itemView(item, title));
            } else {
                itemViewsByUnit.computeIfAbsent(item.getUnitId(), ignored -> new ArrayList<>())
                        .add(itemView(item, title));
            }
        }
        Comparator<CourseOutlineItemView> byPosition =
                Comparator.comparingInt(CourseOutlineItemView::position)
                        .thenComparingLong(CourseOutlineItemView::id);
        topItems.sort(byPosition);
        itemViewsByUnit.values().forEach(items -> items.sort(byPosition));
        Map<Long, List<CourseUnit>> childrenByParent = new HashMap<>();
        List<CourseUnit> roots = new ArrayList<>();
        for (CourseUnit unit : storedUnits) {
            if (unit.getParentId() == null) {
                roots.add(unit);
            } else {
                childrenByParent.computeIfAbsent(unit.getParentId(), ignored -> new ArrayList<>())
                        .add(unit);
            }
        }
        Set<Long> visited = new HashSet<>();
        List<CourseOutlineUnitView> unitViews = roots.stream()
                .sorted(Comparator.comparingInt(CourseUnit::getPosition))
                .map(unit -> buildUnit(unit, childrenByParent, itemViewsByUnit, new LinkedHashSet<>(), visited))
                .toList();
        if (visited.size() != storedUnits.size()) {
            throw new IllegalStateException("课程内容单元结构存在环或无效父单元");
        }
        return new CourseOutlineView(topItems, unitViews);
    }

    private CourseOutlineUnitView buildUnit(
            CourseUnit unit,
            Map<Long, List<CourseUnit>> childrenByParent,
            Map<Long, List<CourseOutlineItemView>> itemsByUnit,
            Set<Long> ancestors,
            Set<Long> visited
    ) {
        if (!ancestors.add(unit.getId())) {
            throw new IllegalStateException("课程内容单元结构存在环");
        }
        if (!visited.add(unit.getId())) {
            throw new IllegalStateException("课程内容单元被重复引用");
        }
        List<CourseOutlineUnitView> children = childrenByParent
                .getOrDefault(unit.getId(), List.of())
                .stream()
                .sorted(Comparator.comparingInt(CourseUnit::getPosition))
                .map(child -> buildUnit(child, childrenByParent, itemsByUnit, new LinkedHashSet<>(ancestors), visited))
                .toList();
        return unitView(unit, children, itemsByUnit.getOrDefault(unit.getId(), List.of()));
    }

    private void validateReplacement(
            List<CourseUnit> storedUnits,
            List<CourseOutlineItem> storedItems,
            List<UnitPlacement> requestedUnits,
            List<ItemPlacement> requestedItems
    ) {
        Set<Long> storedUnitIds = storedUnits.stream()
                .map(CourseUnit::getId)
                .collect(Collectors.toUnmodifiableSet());
        Set<Long> requestedUnitIds = requestedUnits.stream()
                .map(UnitPlacement::unitId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestedUnitIds.size() != requestedUnits.size()) {
            throw new BadRequestException("单元排序中存在重复单元");
        }
        if (!requestedUnitIds.equals(storedUnitIds)) {
            throw new ConflictException("单元集合已变化，请刷新课程内容后重试");
        }
        Set<Long> storedItemIds = storedItems.stream()
                .map(CourseOutlineItem::getId)
                .collect(Collectors.toUnmodifiableSet());
        Set<Long> requestedItemIds = requestedItems.stream()
                .map(ItemPlacement::itemId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestedItemIds.size() != requestedItems.size()) {
            throw new BadRequestException("课程内容排序中存在重复内容");
        }
        if (!requestedItemIds.equals(storedItemIds)) {
            throw new ConflictException("课程内容集合已变化，请刷新课程内容后重试");
        }

        Map<Long, Long> desiredParents = new LinkedHashMap<>();
        Map<Long, List<Integer>> unitPositions = new HashMap<>();
        List<Integer> rootPositions = new ArrayList<>();
        for (UnitPlacement placement : requestedUnits) {
            requirePositivePosition(placement.position());
            if (placement.parentId() != null && !storedUnitIds.contains(placement.parentId())) {
                throw new BadRequestException("单元排序引用了不存在的父单元");
            }
            if (placement.parentId() != null && placement.parentId() == placement.unitId()) {
                throw new BadRequestException("单元不能成为自身的子单元");
            }
            desiredParents.put(placement.unitId(), placement.parentId());
            if (placement.parentId() == null) {
                rootPositions.add(placement.position());
            } else {
                unitPositions.computeIfAbsent(placement.parentId(), ignored -> new ArrayList<>())
                        .add(placement.position());
            }
        }
        requireContiguousPositions(rootPositions, "顶级单元");
        for (List<Integer> positions : unitPositions.values()) {
            requireContiguousPositions(positions, "同级单元");
        }
        requireAcyclic(desiredParents);

        Map<Long, List<Integer>> itemPositions = new HashMap<>();
        for (ItemPlacement placement : requestedItems) {
            requirePositivePosition(placement.position());
            if (placement.unitId() != null && !storedUnitIds.contains(placement.unitId())) {
                throw new BadRequestException("课程内容排序引用了不存在的单元");
            }
            itemPositions.computeIfAbsent(
                            placement.unitId() == null ? 0L : placement.unitId(),
                            ignored -> new ArrayList<>())
                    .add(placement.position());
        }
        for (List<Integer> positions : itemPositions.values()) {
            requireContiguousPositions(positions, "同组课程内容");
        }
    }

    private void requireAcyclic(Map<Long, Long> desiredParents) {
        for (Long unitId : desiredParents.keySet()) {
            Set<Long> path = new HashSet<>();
            Long cursor = unitId;
            while (cursor != null) {
                if (!path.add(cursor)) {
                    throw new BadRequestException("单元层级存在环");
                }
                cursor = desiredParents.get(cursor);
            }
        }
    }

    /** 整体排序前先把所有行挪到不冲突的暂存位,避免唯一键 (scope, position) 在中途相撞 */
    private void stageUnits(List<CourseUnit> storedUnits) {
        int stagingPosition = storedUnits.size() + 1;
        for (CourseUnit unit : storedUnits) {
            unit.moveTo(unit.getParentId(), stagingPosition++);
            courses.requireMutation(units.updateById(unit), "单元排序期间状态已变化，保存未生效");
        }
    }

    private void stageItems(List<CourseOutlineItem> storedItems) {
        int stagingPosition = storedItems.size() + 1;
        for (CourseOutlineItem item : storedItems) {
            item.moveTo(item.getUnitId(), stagingPosition++);
            courses.requireMutation(outlineItems.updateById(item), "课程内容排序期间状态已变化，保存未生效");
        }
    }

    /** 删除一个单元后,其后的同级单元依次前移一位(升序更新不会触碰唯一键) */
    private void closeUnitGap(long courseId, Long parentId, int removedPosition) {
        for (CourseUnit unit : childUnits(courseId, parentId)) {
            if (unit.getPosition() > removedPosition) {
                unit.moveTo(parentId, unit.getPosition() - 1);
                courses.requireMutation(units.updateById(unit), "单元删除后的顺序整理未生效");
            }
        }
    }

    private void closeItemGap(long courseId, Long unitId, int removedPosition) {
        for (CourseOutlineItem item : itemsInUnit(courseId, unitId)) {
            if (item.getPosition() > removedPosition) {
                item.moveTo(unitId, item.getPosition() - 1);
                courses.requireMutation(outlineItems.updateById(item), "课程内容移除后的顺序整理未生效");
            }
        }
    }

    private void requireContiguousPositions(List<Integer> positions, String scope) {
        List<Integer> sorted = positions.stream().sorted().toList();
        for (int index = 0; index < sorted.size(); index++) {
            if (sorted.get(index) != index + 1) {
                throw new BadRequestException(scope + "顺序必须从 1 开始且连续，不能重复或断号");
            }
        }
    }

    private void requirePositivePosition(int position) {
        if (position < 1) {
            throw new BadRequestException("排序位置必须大于等于 1");
        }
    }

    private CourseUnit requireUnit(long courseId, long unitId) {
        CourseUnit unit = units.selectOne(new LambdaQueryWrapper<CourseUnit>()
                .eq(CourseUnit::getCourseId, courseId)
                .eq(CourseUnit::getId, unitId));
        if (unit == null) {
            throw new NotFoundException("课程单元不存在");
        }
        return unit;
    }

    private CourseOutlineItem requireOutlineItem(long courseId, long itemId) {
        CourseOutlineItem item = outlineItems.selectOne(new LambdaQueryWrapper<CourseOutlineItem>()
                .eq(CourseOutlineItem::getCourseId, courseId)
                .eq(CourseOutlineItem::getId, itemId));
        if (item == null) {
            throw new NotFoundException("课程内容不存在");
        }
        return item;
    }

    private List<CourseUnit> courseUnits(long courseId) {
        return units.selectList(new LambdaQueryWrapper<CourseUnit>()
                .eq(CourseUnit::getCourseId, courseId)
                .orderByAsc(CourseUnit::getParentId)
                .orderByAsc(CourseUnit::getPosition)
                .orderByAsc(CourseUnit::getId));
    }

    private List<CourseUnit> childUnits(long courseId, Long parentId) {
        return units.selectList(new LambdaQueryWrapper<CourseUnit>()
                .eq(CourseUnit::getCourseId, courseId)
                .isNull(parentId == null, CourseUnit::getParentId)
                .eq(parentId != null, CourseUnit::getParentId, parentId)
                .orderByAsc(CourseUnit::getPosition)
                .orderByAsc(CourseUnit::getId));
    }

    private List<CourseOutlineItem> courseItems(long courseId) {
        return outlineItems.selectList(new LambdaQueryWrapper<CourseOutlineItem>()
                .eq(CourseOutlineItem::getCourseId, courseId)
                .orderByAsc(CourseOutlineItem::getUnitId)
                .orderByAsc(CourseOutlineItem::getPosition)
                .orderByAsc(CourseOutlineItem::getId));
    }

    private List<CourseOutlineItem> itemsInUnit(long courseId, Long unitId) {
        return outlineItems.selectList(new LambdaQueryWrapper<CourseOutlineItem>()
                .eq(CourseOutlineItem::getCourseId, courseId)
                .eq(unitId != null, CourseOutlineItem::getUnitId, unitId)
                .isNull(unitId == null, CourseOutlineItem::getUnitId)
                .orderByAsc(CourseOutlineItem::getPosition)
                .orderByAsc(CourseOutlineItem::getId));
    }

    private LambdaQueryWrapper<CourseOutlineItem> contentQuery(
            long courseId, CourseOutlineItemType itemType, long contentId
    ) {
        return new LambdaQueryWrapper<CourseOutlineItem>()
                .eq(CourseOutlineItem::getCourseId, courseId)
                .eq(CourseOutlineItem.contentColumn(itemType), contentId);
    }

    private Map<Long, CourseUnit> indexUnits(List<CourseUnit> storedUnits) {
        Map<Long, CourseUnit> result = new HashMap<>();
        for (CourseUnit unit : storedUnits) {
            result.put(unit.getId(), unit);
        }
        return result;
    }

    private Map<Long, CourseOutlineItem> indexItems(List<CourseOutlineItem> storedItems) {
        Map<Long, CourseOutlineItem> result = new HashMap<>();
        for (CourseOutlineItem item : storedItems) {
            result.put(item.getId(), item);
        }
        return result;
    }

    private CourseOutlineUnitView unitView(
            CourseUnit unit,
            List<CourseOutlineUnitView> children,
            List<CourseOutlineItemView> items
    ) {
        return new CourseOutlineUnitView(
                unit.getId(), unit.getTitle(), unit.getPosition(), children, items);
    }

    private CourseOutlineItemView itemView(CourseOutlineItem item, String title) {
        return new CourseOutlineItemView(
                item.getId(), item.getItemType(), item.contentId(), title, item.getPosition());
    }

    /** items = 顶层内容(不属于任何单元,显示在单元之前) */
    public record CourseOutlineView(List<CourseOutlineItemView> items, List<CourseOutlineUnitView> units) {
        public CourseOutlineView {
            items = List.copyOf(items);
            units = List.copyOf(units);
        }
    }

    public record CourseOutlineUnitView(
            long id,
            String title,
            int position,
            List<CourseOutlineUnitView> children,
            List<CourseOutlineItemView> items
    ) {
        public CourseOutlineUnitView {
            children = List.copyOf(children);
            items = List.copyOf(items);
        }
    }

    public record CourseOutlineItemView(
            long id,
            CourseOutlineItemType itemType,
            long contentId,
            String title,
            int position
    ) {
    }

    public record UnitPlacement(
            long unitId,
            @Schema(nullable = true) Long parentId,
            int position
    ) {
    }

    public record ItemPlacement(long itemId, @Schema(nullable = true) Long unitId, int position) {
    }
}
