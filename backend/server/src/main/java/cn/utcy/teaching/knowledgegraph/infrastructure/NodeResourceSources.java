package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineSourceProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 节点挂载的内容来源:与课程内容同一契约({@link CourseOutlineSourceProvider}),
 * 挂载时锁内容行并要求就绪,读取时向内容模块取标题(不做快照)。
 */
@Component
public class NodeResourceSources {

    private final Map<CourseOutlineItemType, CourseOutlineSourceProvider> providers;

    NodeResourceSources(List<CourseOutlineSourceProvider> providers) {
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

    public String requireLinkable(long courseId, CourseOutlineItemType type, long contentId) {
        return providers.get(type).requireLinkable(courseId, contentId);
    }

    /** 挂载行与内容行同事务删除,缺标题即不变量被破坏 */
    public Map<Long, String> titles(long courseId, CourseOutlineItemType type, Set<Long> contentIds) {
        if (contentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> titles = providers.get(type).requireTitles(courseId, contentIds);
        for (Long contentId : contentIds) {
            if (!titles.containsKey(contentId)) {
                throw new IllegalStateException("课程内容提供者未返回所请求的内容：" + type + "/" + contentId);
            }
        }
        return titles;
    }
}
