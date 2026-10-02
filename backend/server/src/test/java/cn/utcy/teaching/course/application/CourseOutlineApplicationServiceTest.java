package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineSourceProvider;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.UnitPlacement;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService.ItemPlacement;
import cn.utcy.teaching.course.domain.Course;
import cn.utcy.teaching.course.domain.CourseUnit;
import cn.utcy.teaching.course.domain.CourseOutlineItem;
import cn.utcy.teaching.course.infrastructure.CourseUnitMapper;
import cn.utcy.teaching.course.infrastructure.CourseOutlineItemMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseOutlineApplicationServiceTest {

    private static final long COURSE_ID = 7L;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CourseUnit.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CourseOutlineItem.class);
    }

    @Test
    void learnerAndManagementOutlineAreTheSameView() {
        Fixture fixture = fixture();
        when(fixture.units.selectList(any())).thenReturn(List.of(unit(1L, null, 1)));
        when(fixture.items.selectList(any())).thenReturn(List.of(
                item(11L, 1L, 101L, 1),
                item(12L, 1L, 102L, 2)));
        when(fixture.materials.requireTitles(COURSE_ID, Set.of(101L, 102L))).thenReturn(Map.of(
                101L, "单元讲义",
                102L, "课程示例"));

        var learner = fixture.service.get(COURSE_ID);
        var management = fixture.service.getForManagement(COURSE_ID);

        assertThat(learner).isEqualTo(management);
        assertThat(learner.units()).singleElement().satisfies(view -> assertThat(view.items())
                .extracting(item -> item.contentId(), item -> item.title(), item -> item.position())
                .containsExactly(
                        tuple(101L, "单元讲义", 1),
                        tuple(102L, "课程示例", 2)));
        verify(fixture.courses).requireLearningAccess(COURSE_ID, fixture.actor);
        verify(fixture.courses).requireManagementAccess(COURSE_ID, fixture.actor);
    }

    @Test
    void readingOutlineDoesNotValidatePositions() {
        Fixture fixture = fixture();
        when(fixture.units.selectList(any())).thenReturn(List.of(unit(1L, null, 1)));
        when(fixture.items.selectList(any())).thenReturn(List.of(
                item(11L, 1L, 101L, 1),
                item(12L, 1L, 102L, 3)));
        when(fixture.materials.requireTitles(COURSE_ID, Set.of(101L, 102L))).thenReturn(Map.of(
                101L, "单元讲义",
                102L, "课程示例"));

        var outline = fixture.service.getTrusted(COURSE_ID);

        assertThat(outline.units()).singleElement().satisfies(view -> assertThat(view.items())
                .extracting(item -> item.position())
                .containsExactly(1, 3));
    }

    @Test
    void removingItemRenumbersFollowingSiblingsInOnePass() {
        Fixture fixture = fixture();
        CourseOutlineItem first = item(11L, 1L, 101L, 1);
        CourseOutlineItem removed = item(12L, 1L, 102L, 2);
        CourseOutlineItem third = item(13L, 1L, 103L, 3);
        when(fixture.courses.requireManageableForUpdate(COURSE_ID, fixture.actor))
                .thenReturn(course());
        when(fixture.items.selectOne(any())).thenReturn(removed);
        when(fixture.items.deleteById(12L)).thenReturn(1);
        when(fixture.items.selectList(any())).thenReturn(List.of(first, third));
        when(fixture.items.updateById((CourseOutlineItem) any())).thenReturn(1);

        fixture.service.removeItem(COURSE_ID, 12L);

        verify(fixture.items).deleteById(12L);
        verify(fixture.items, times(1)).updateById((CourseOutlineItem) any());
        verify(fixture.items).updateById(third);
        verify(fixture.items, never()).updateById(first);
        assertThat(third.getPosition()).isEqualTo(2);
        assertThat(first.getPosition()).isEqualTo(1);
    }

    @Test
    void addItemWithoutUnitAppendsToTopLevel() {
        Fixture fixture = fixture();
        when(fixture.courses.requireManageableForUpdate(COURSE_ID, fixture.actor))
                .thenReturn(course());
        when(fixture.materials.requireLinkable(COURSE_ID, 101L)).thenReturn("顶层讲义");
        when(fixture.items.exists(any())).thenReturn(false);
        when(fixture.items.selectList(any())).thenReturn(List.of(item(11L, null, 102L, 1)));
        when(fixture.items.insert((CourseOutlineItem) any())).thenAnswer(invocation -> {
            CourseOutlineItem inserted = invocation.getArgument(0);
            ReflectionTestUtils.setField(inserted, "id", 99L);
            return 1;
        });

        var view = fixture.service.addItem(COURSE_ID, null, CourseOutlineItemType.MATERIAL, 101L);

        assertThat(view.title()).isEqualTo("顶层讲义");
        assertThat(view.position()).isEqualTo(2);
        verify(fixture.units, never()).selectOne(any());
    }

    @Test
    void removingTopLevelItemRenumbersFollowingTopSiblings() {
        Fixture fixture = fixture();
        CourseOutlineItem removed = item(11L, null, 101L, 1);
        CourseOutlineItem second = item(12L, null, 102L, 2);
        when(fixture.courses.requireManageableForUpdate(COURSE_ID, fixture.actor))
                .thenReturn(course());
        when(fixture.items.selectOne(any())).thenReturn(removed);
        when(fixture.items.deleteById(11L)).thenReturn(1);
        when(fixture.items.selectList(any())).thenReturn(List.of(second));
        when(fixture.items.updateById((CourseOutlineItem) any())).thenReturn(1);

        fixture.service.removeItem(COURSE_ID, 11L);

        assertThat(second.getPosition()).isEqualTo(1);
        assertThat(second.getUnitId()).isNull();
        verify(fixture.items).updateById(second);
    }

    @Test
    void deletingUnitRenumbersFollowingSiblings() {
        Fixture fixture = fixture();
        CourseUnit first = unit(1L, null, 1);
        CourseUnit removed = unit(2L, null, 2);
        CourseUnit third = unit(3L, null, 3);
        when(fixture.courses.requireManageableForUpdate(COURSE_ID, fixture.actor))
                .thenReturn(course());
        when(fixture.units.selectOne(any())).thenReturn(removed);
        when(fixture.units.deleteById(2L)).thenReturn(1);
        when(fixture.units.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(first, third));
        when(fixture.units.updateById((CourseUnit) any())).thenReturn(1);

        fixture.service.deleteUnit(COURSE_ID, 2L);

        verify(fixture.units).deleteById(2L);
        verify(fixture.units, times(1)).updateById((CourseUnit) any());
        verify(fixture.units).updateById(third);
        verify(fixture.units, never()).updateById(first);
        assertThat(third.getPosition()).isEqualTo(2);
        assertThat(first.getPosition()).isEqualTo(1);
    }

    @Test
    void addItemAppendsLinkableContentAtEndOfUnit() {
        Fixture fixture = addFixture();
        when(fixture.materials.requireLinkable(COURSE_ID, 101L)).thenReturn("单元讲义");
        when(fixture.items.selectList(any())).thenReturn(List.of(item(11L, 1L, 100L, 1)));

        var item = fixture.service.addItem(
                COURSE_ID,
                1L,
                CourseOutlineItemType.MATERIAL,
                101L);

        assertThat(item.id()).isEqualTo(13L);
        assertThat(item.itemType()).isEqualTo(CourseOutlineItemType.MATERIAL);
        assertThat(item.contentId()).isEqualTo(101L);
        assertThat(item.title()).isEqualTo("单元讲义");
        assertThat(item.position()).isEqualTo(2);
        verify(fixture.items).insert(any(CourseOutlineItem.class));
    }

    @Test
    void addItemAllowsRepeatedContentInCourse() {
        Fixture fixture = addFixture();
        when(fixture.materials.requireLinkable(COURSE_ID, 101L)).thenReturn("单元讲义");
        when(fixture.items.selectList(any())).thenReturn(List.of(item(11L, 1L, 101L, 1)));

        var item = fixture.service.addItem(
                COURSE_ID,
                1L,
                CourseOutlineItemType.MATERIAL,
                101L);

        assertThat(item.contentId()).isEqualTo(101L);
        assertThat(item.position()).isEqualTo(2);
        verify(fixture.items, never()).exists(any());
        verify(fixture.items).insert(any(CourseOutlineItem.class));
    }

    @Test
    void providerRejectionShortCircuitsBeforeWrite() {
        Fixture fixture = addFixture();
        doThrow(new ConflictException("文件尚未上传完成"))
                .when(fixture.materials)
                .requireLinkable(COURSE_ID, 101L);

        assertThatThrownBy(() -> fixture.service.addItem(
                COURSE_ID,
                1L,
                CourseOutlineItemType.MATERIAL,
                101L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("文件尚未上传完成");

        verify(fixture.items, never()).exists(any());
        verify(fixture.items, never()).insert(any(CourseOutlineItem.class));
    }

    @Test
    void fullOrderReplacementRejectsMissingItemsAndUnitCycles() {
        Fixture fixture = fixture();
        List<CourseUnit> units = List.of(
                unit(1L, null, 1),
                unit(2L, null, 2));
        List<CourseOutlineItem> items = List.of(
                item(11L, 1L, 101L, 1),
                item(12L, 2L, 102L, 1));
        when(fixture.units.selectList(any())).thenReturn(units);
        when(fixture.items.selectList(any())).thenReturn(items);

        assertThatThrownBy(() -> fixture.service.replaceOrder(
                COURSE_ID,
                List.of(
                        new UnitPlacement(1L, null, 1),
                        new UnitPlacement(2L, null, 2)),
                List.of(new ItemPlacement(11L, 1L, 1))))
                .isInstanceOf(ConflictException.class)
                .hasMessage("课程内容集合已变化，请刷新课程内容后重试");

        assertThatThrownBy(() -> fixture.service.replaceOrder(
                COURSE_ID,
                List.of(
                        new UnitPlacement(1L, 2L, 1),
                        new UnitPlacement(2L, 1L, 1)),
                List.of(
                        new ItemPlacement(11L, 1L, 1),
                        new ItemPlacement(12L, 2L, 1))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("单元层级存在环");

        verify(fixture.units, never()).updateById(any(CourseUnit.class));
        verify(fixture.items, never()).updateById(any(CourseOutlineItem.class));
    }

    @Test
    void fullOrderReplacementMovesUnitsAndItemsAsOneTree() {
        Fixture fixture = fixture();
        List<CourseUnit> units = new ArrayList<>(List.of(
                unit(1L, null, 1),
                unit(2L, null, 2)));
        List<CourseOutlineItem> items = new ArrayList<>(List.of(
                item(11L, 1L, 101L, 1),
                item(12L, 2L, 102L, 1)));
        when(fixture.units.selectList(any())).thenReturn(units);
        when(fixture.items.selectList(any())).thenReturn(items);
        when(fixture.units.updateById((CourseUnit) any())).thenReturn(1);
        when(fixture.items.updateById((CourseOutlineItem) any())).thenReturn(1);
        when(fixture.materials.requireTitles(COURSE_ID, Set.of(101L, 102L))).thenReturn(Map.of(
                101L, "单元讲义",
                102L, "课程示例"));

        var outline = fixture.service.replaceOrder(
                COURSE_ID,
                List.of(
                        new UnitPlacement(2L, null, 1),
                        new UnitPlacement(1L, 2L, 1)),
                List.of(
                        new ItemPlacement(12L, 2L, 1),
                        new ItemPlacement(11L, 2L, 2)));

        assertThat(outline.units()).singleElement().satisfies(root -> {
            assertThat(root.id()).isEqualTo(2L);
            assertThat(root.children()).singleElement()
                    .satisfies(child -> assertThat(child.id()).isEqualTo(1L));
            assertThat(root.items())
                    .extracting(item -> item.id())
                    .containsExactly(12L, 11L);
        });
        assertThat(items)
                .extracting(CourseOutlineItem::getUnitId)
                .containsOnly(2L);
        assertThat(units)
                .filteredOn(unit -> unit.getId() == 1L)
                .singleElement()
                .satisfies(unit -> assertThat(unit.getParentScope()).isEqualTo(2L));
        assertThat(units)
                .filteredOn(unit -> unit.getId() == 2L)
                .singleElement()
                .satisfies(unit -> assertThat(unit.getParentScope()).isZero());
    }

    @Test
    void deletingUnitCascadesToDescendantsBottomUpThenClosesSiblingGap() {
        Fixture fixture = fixture();
        CourseUnit root = unit(1L, null, 1);
        CourseUnit child = unit(2L, 1L, 1);
        CourseUnit grandchild = unit(3L, 2L, 1);
        CourseUnit sibling = unit(4L, null, 2);
        when(fixture.courses.requireManageableForUpdate(COURSE_ID, fixture.actor))
                .thenReturn(course());
        when(fixture.units.selectOne(any())).thenReturn(root);
        when(fixture.units.selectList(any()))
                .thenReturn(List.of(child))
                .thenReturn(List.of(grandchild))
                .thenReturn(List.of())
                .thenReturn(List.of(sibling));
        when(fixture.units.deleteById(anyLong())).thenReturn(1);
        when(fixture.units.updateById((CourseUnit) any())).thenReturn(1);

        fixture.service.deleteUnit(COURSE_ID, 1L);

        InOrder order = inOrder(fixture.units, fixture.items);
        order.verify(fixture.items).delete(any());
        order.verify(fixture.units).deleteById(3L);
        order.verify(fixture.items).delete(any());
        order.verify(fixture.units).deleteById(2L);
        order.verify(fixture.items).delete(any());
        order.verify(fixture.units).deleteById(1L);
        order.verify(fixture.units).updateById(sibling);
        verify(fixture.units, never()).deleteById(4L);
        verify(fixture.units, times(1)).updateById((CourseUnit) any());
        assertThat(sibling.getPosition()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletingUnitRemovesItsOutlineItemsByCourseAndUnit() {
        Fixture fixture = fixture();
        when(fixture.courses.requireManageableForUpdate(COURSE_ID, fixture.actor))
                .thenReturn(course());
        when(fixture.units.selectOne(any())).thenReturn(unit(1L, null, 1));
        when(fixture.units.selectList(any())).thenReturn(List.of());
        when(fixture.units.deleteById(1L)).thenReturn(1);

        fixture.service.deleteUnit(COURSE_ID, 1L);

        ArgumentCaptor<LambdaQueryWrapper<CourseOutlineItem>> deletion =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fixture.items).delete(deletion.capture());
        LambdaQueryWrapper<CourseOutlineItem> wrapper = deletion.getValue();
        assertThat(wrapper.getSqlSegment()).contains("course_id", "unit_id");
        assertThat(wrapper.getParamNameValuePairs().values()).containsExactlyInAnyOrder(COURSE_ID, 1L);
        verify(fixture.items, never()).exists(any());
        verify(fixture.items, never()).deleteById(anyLong());
        verify(fixture.units).deleteById(1L);
    }

    private Fixture addFixture() {
        Fixture fixture = fixture();
        when(fixture.courses.requireManageableForUpdate(COURSE_ID, fixture.actor))
                .thenReturn(course());
        when(fixture.units.selectOne(any())).thenReturn(unit(1L, null, 1));
        when(fixture.items.exists(any())).thenReturn(false);
        when(fixture.items.selectList(any())).thenReturn(List.of());
        when(fixture.items.insert((CourseOutlineItem) any())).thenAnswer(invocation -> {
            ReflectionTestUtils.setField(
                    invocation.getArgument(0, CourseOutlineItem.class),
                    "id",
                    13L);
            return 1;
        });
        return fixture;
    }

    private Fixture fixture() {
        Actor actor = new Actor(11L, "teacher", SystemRole.TEACHER);
        CourseApplicationService courses = mock(CourseApplicationService.class);
        CourseUnitMapper units = mock(CourseUnitMapper.class);
        CourseOutlineItemMapper items = mock(CourseOutlineItemMapper.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(actor);

        Map<CourseOutlineItemType, CourseOutlineSourceProvider> providers =
                new EnumMap<>(CourseOutlineItemType.class);
        for (CourseOutlineItemType type : CourseOutlineItemType.values()) {
            CourseOutlineSourceProvider provider = mock(CourseOutlineSourceProvider.class);
            when(provider.type()).thenReturn(type);
            providers.put(type, provider);
        }
        CourseOutlineSourceProvider materials = providers.get(CourseOutlineItemType.MATERIAL);
        CourseOutlineApplicationService service = new CourseOutlineApplicationService(
                courses,
                units,
                items,
                currentActor,
                new CourseOutlineSourceRegistry(List.copyOf(providers.values())));
        return new Fixture(service, courses, units, items, materials, actor);
    }

    private Course course() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new Course(
                COURSE_ID,
                11L,
                "A1B2C3D4E5",
                "Java 程序设计",
                "",
                true,
                now,
                now);
    }

    private CourseUnit unit(long id, Long parentId, int position) {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new CourseUnit(
                id,
                COURSE_ID,
                parentId,
                "单元 " + id,
                position,
                now,
                now);
    }

    private CourseOutlineItem item(long id, Long unitId, long contentId, int position) {
        return new CourseOutlineItem(
                id,
                COURSE_ID,
                unitId,
                CourseOutlineItemType.MATERIAL,
                contentId,
                null,
                null,
                position,
                Instant.parse("2026-07-29T00:00:00Z"));
    }

    private record Fixture(
            CourseOutlineApplicationService service,
            CourseApplicationService courses,
            CourseUnitMapper units,
            CourseOutlineItemMapper items,
            CourseOutlineSourceProvider materials,
            Actor actor
    ) {
    }
}
