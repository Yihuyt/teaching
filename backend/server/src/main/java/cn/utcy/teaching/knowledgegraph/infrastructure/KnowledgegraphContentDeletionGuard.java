package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.shared.course.CourseContentDeletionGuard;
import cn.utcy.teaching.shared.course.CourseContentReferenceSource;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphChangeMarker;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeGraph;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNodeResource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 课程内容删除时的图谱善后:以它为来源的构建任务保留、只解除来源资料引用;
 * 挂在节点上的资源随内容卸载(锁图谱行 → 删挂载 → touch + 发事件,检索索引随之同步)。
 * 删除确认时,挂在节点上即"有关联"。只依赖 Mapper 与变更发布器,避免与内容服务形成循环依赖。
 */
@Component
class KnowledgegraphContentDeletionGuard implements CourseContentDeletionGuard, CourseContentReferenceSource {

    private final KnowledgeGraphBuildMapper builds;
    private final KnowledgeGraphMapper graphs;
    private final KnowledgeNodeResourceMapper resources;
    private final KnowledgeGraphChangeMarker changes;

    KnowledgegraphContentDeletionGuard(KnowledgeGraphBuildMapper builds, KnowledgeGraphMapper graphs,
                                       KnowledgeNodeResourceMapper resources, KnowledgeGraphChangeMarker changes) {
        this.builds = builds;
        this.graphs = graphs;
        this.resources = resources;
        this.changes = changes;
    }

    @Override
    public void beforeContentDeleted(long courseId, CourseOutlineItemType itemType, long contentId) {
        if (itemType == CourseOutlineItemType.MATERIAL) {
            builds.detachMaterial(contentId);
        }
        for (Long graphId : attachedGraphIds(courseId, itemType, contentId)) {
            KnowledgeGraph graph = graphs.selectForUpdate(courseId, graphId);
            if (graph == null) {
                continue;
            }
            resources.delete(new LambdaQueryWrapper<KnowledgeNodeResource>()
                    .eq(KnowledgeNodeResource::getGraphId, graphId)
                    .eq(KnowledgeNodeResource::getItemType, itemType)
                    .eq(KnowledgeNodeResource::getContentId, contentId));
            changes.changed(graph);
        }
    }

    @Override
    public boolean references(long courseId, CourseOutlineItemType itemType, long contentId) {
        return !attachedGraphIds(courseId, itemType, contentId).isEmpty();
    }

    private List<Long> attachedGraphIds(long courseId, CourseOutlineItemType itemType, long contentId) {
        List<Long> courseGraphs = graphs.selectList(new LambdaQueryWrapper<KnowledgeGraph>()
                        .select(KnowledgeGraph::getId)
                        .eq(KnowledgeGraph::getCourseId, courseId))
                .stream()
                .map(KnowledgeGraph::getId)
                .toList();
        if (courseGraphs.isEmpty()) {
            return List.of();
        }
        return resources.selectList(new LambdaQueryWrapper<KnowledgeNodeResource>()
                        .select(KnowledgeNodeResource::getGraphId)
                        .in(KnowledgeNodeResource::getGraphId, courseGraphs)
                        .eq(KnowledgeNodeResource::getItemType, itemType)
                        .eq(KnowledgeNodeResource::getContentId, contentId))
                .stream()
                .map(KnowledgeNodeResource::getGraphId)
                .distinct()
                .toList();
    }
}
