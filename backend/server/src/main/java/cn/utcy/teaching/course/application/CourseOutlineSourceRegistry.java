package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineSourceProvider;
import cn.utcy.teaching.course.domain.CourseOutlineItem;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class CourseOutlineSourceRegistry {

    private final Map<CourseOutlineItemType, CourseOutlineSourceProvider> providers;

    CourseOutlineSourceRegistry(List<CourseOutlineSourceProvider> providers) {
        Map<CourseOutlineItemType, CourseOutlineSourceProvider> registered =
                new EnumMap<>(CourseOutlineItemType.class);
        for (CourseOutlineSourceProvider provider : providers) {
            if (registered.put(provider.type(), provider) != null) {
                throw new IllegalStateException("课程内容类型存在多个提供者：" + provider.type());
            }
        }
        for (CourseOutlineItemType type : CourseOutlineItemType.values()) {
            if (!registered.containsKey(type)) {
                throw new IllegalStateException("课程内容类型缺少提供者：" + type);
            }
        }
        this.providers = Map.copyOf(registered);
    }

    CourseOutlineSourceProvider provider(CourseOutlineItemType type) {
        return providers.get(type);
    }

    /** 按类型分组向各提供者取标题;提供者漏返任一 id 视为编程错误 */
    Map<ContentKey, String> requireTitles(long courseId, List<CourseOutlineItem> items) {
        Map<CourseOutlineItemType, Set<Long>> idsByType = items.stream()
                .collect(Collectors.groupingBy(
                        CourseOutlineItem::getItemType,
                        () -> new EnumMap<>(CourseOutlineItemType.class),
                        Collectors.mapping(CourseOutlineItem::contentId, Collectors.toUnmodifiableSet())));
        Map<ContentKey, String> titles = new HashMap<>();
        for (Map.Entry<CourseOutlineItemType, Set<Long>> entry : idsByType.entrySet()) {
            Map<Long, String> typed = provider(entry.getKey()).requireTitles(courseId, entry.getValue());
            for (Long contentId : entry.getValue()) {
                String title = typed.get(contentId);
                if (title == null) {
                    throw new IllegalStateException(
                            "课程内容提供者未返回所请求的内容：" + entry.getKey() + "/" + contentId);
                }
                titles.put(new ContentKey(entry.getKey(), contentId), title);
            }
        }
        return Map.copyOf(titles);
    }

    record ContentKey(CourseOutlineItemType itemType, long contentId) {
    }
}
