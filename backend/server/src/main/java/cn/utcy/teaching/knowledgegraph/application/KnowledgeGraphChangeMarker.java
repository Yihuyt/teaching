package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeGraph;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * "图谱内容变了"的唯一定义:touch updated_at,并以行更新结果兜住并发冲突。
 * 编辑器与内容删除守卫共用;调用方已持有图谱行锁,且必须在事务内。
 */
@Component
public class KnowledgeGraphChangeMarker {

    private final KnowledgeGraphMapper graphs;
    private final Clock clock;

    public KnowledgeGraphChangeMarker(KnowledgeGraphMapper graphs, Clock clock) {
        this.graphs = graphs;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(KnowledgeGraph graph) {
        graph.touch(clock.instant());
        if (graphs.updateById(graph) != 1) {
            throw new ConflictException("知识图谱状态已变化，保存未生效");
        }
    }
}
