package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineSourceProvider;
import cn.utcy.teaching.course.domain.CourseOutlineItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CourseOutlineSourceRegistryTest {

    @Test
    void duplicateProviderForOneTypeIsAConfigurationError() {
        List<CourseOutlineSourceProvider> providers = new ArrayList<>(allProviders());
        providers.add(provider(CourseOutlineItemType.MATERIAL));

        assertThatThrownBy(() -> new CourseOutlineSourceRegistry(providers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("存在多个提供者")
                .hasMessageContaining("MATERIAL");
    }

    @Test
    void missingProviderForAnyTypeIsAConfigurationError() {
        List<CourseOutlineSourceProvider> providers = List.of(
                provider(CourseOutlineItemType.MATERIAL),
                provider(CourseOutlineItemType.QUESTION));

        assertThatThrownBy(() -> new CourseOutlineSourceRegistry(providers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少提供者")
                .hasMessageContaining("PROGRAMMING_PROBLEM");
    }

    @Test
    void providerOmittingARequestedIdIsAProgrammingError() {
        List<CourseOutlineSourceProvider> providers = allProviders();
        CourseOutlineSourceProvider materials = providers.get(0);
        when(materials.requireTitles(7L, Set.of(101L, 102L))).thenReturn(Map.of(101L, "讲义"));
        CourseOutlineSourceRegistry registry = new CourseOutlineSourceRegistry(providers);

        assertThatThrownBy(() -> registry.requireTitles(7L, List.of(
                materialItem(11L, 101L),
                materialItem(12L, 102L))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATERIAL/102");
    }

    private List<CourseOutlineSourceProvider> allProviders() {
        List<CourseOutlineSourceProvider> providers = new ArrayList<>();
        for (CourseOutlineItemType type : CourseOutlineItemType.values()) {
            providers.add(provider(type));
        }
        return providers;
    }

    private CourseOutlineSourceProvider provider(CourseOutlineItemType type) {
        CourseOutlineSourceProvider provider = mock(CourseOutlineSourceProvider.class);
        when(provider.type()).thenReturn(type);
        return provider;
    }

    private CourseOutlineItem materialItem(long id, long materialId) {
        return new CourseOutlineItem(
                id,
                7L,
                1L,
                CourseOutlineItemType.MATERIAL,
                materialId,
                null,
                null,
                (int) id,
                Instant.parse("2026-07-29T00:00:00Z"));
    }
}
