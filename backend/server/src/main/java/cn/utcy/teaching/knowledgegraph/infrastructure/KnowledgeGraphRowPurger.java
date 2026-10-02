package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.knowledgegraph.domain.KnowledgeEdge;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeGraph;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNode;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNodeResource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识图谱行的显式删除(数据库不设外键):
 * 图谱 → 挂载 → 关系 → 节点 → 图谱;
 * 构建 → 章节。删图谱 / 删构建 / 删课程共用,必须在业务事务内调用。
 */
@Component
public class KnowledgeGraphRowPurger {

    private final KnowledgeGraphMapper graphs;
    private final KnowledgeNodeMapper nodes;
    private final KnowledgeEdgeMapper edges;
    private final KnowledgeNodeResourceMapper resources;
    private final KnowledgeGraphBuildMapper builds;
    private final BuildSectionMapper sections;

    KnowledgeGraphRowPurger(KnowledgeGraphMapper graphs, KnowledgeNodeMapper nodes, KnowledgeEdgeMapper edges,
                            KnowledgeNodeResourceMapper resources, KnowledgeGraphBuildMapper builds,
                            BuildSectionMapper sections) {
        this.graphs = graphs;
        this.nodes = nodes;
        this.edges = edges;
        this.resources = resources;
        this.builds = builds;
        this.sections = sections;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeBuilds(List<Long> buildIds) {
        if (buildIds.isEmpty()) {
            return;
        }
        sections.delete(new LambdaQueryWrapper<BuildSectionEntity>().in(BuildSectionEntity::getBuildId, buildIds));
        builds.delete(new LambdaQueryWrapper<KnowledgeGraphBuildEntity>().in(KnowledgeGraphBuildEntity::getId, buildIds));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeGraphs(List<Long> graphIds) {
        if (graphIds.isEmpty()) {
            return;
        }
        resources.delete(new LambdaQueryWrapper<KnowledgeNodeResource>().in(KnowledgeNodeResource::getGraphId, graphIds));
        edges.delete(new LambdaQueryWrapper<KnowledgeEdge>().in(KnowledgeEdge::getGraphId, graphIds));
        nodes.delete(new LambdaQueryWrapper<KnowledgeNode>().in(KnowledgeNode::getGraphId, graphIds));
        graphs.delete(new LambdaQueryWrapper<KnowledgeGraph>().in(KnowledgeGraph::getId, graphIds));
    }

    void purgeCourse(long courseId) {
        purgeBuilds(builds.selectList(new LambdaQueryWrapper<KnowledgeGraphBuildEntity>()
                        .select(KnowledgeGraphBuildEntity::getId)
                        .eq(KnowledgeGraphBuildEntity::getCourseId, courseId))
                .stream()
                .map(KnowledgeGraphBuildEntity::getId)
                .toList());
        purgeGraphs(graphs.selectList(new LambdaQueryWrapper<KnowledgeGraph>()
                        .select(KnowledgeGraph::getId)
                        .eq(KnowledgeGraph::getCourseId, courseId))
                .stream()
                .map(KnowledgeGraph::getId)
                .toList());
    }
}
