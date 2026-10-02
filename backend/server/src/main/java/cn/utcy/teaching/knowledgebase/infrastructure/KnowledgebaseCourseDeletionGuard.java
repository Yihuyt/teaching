package cn.utcy.teaching.knowledgebase.infrastructure;

import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import cn.utcy.teaching.knowledgebase.infrastructure.EmbeddingSignature;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class KnowledgebaseCourseDeletionGuard implements CourseDeletionGuard {

    private final KnowledgeBaseMapper knowledgeBases;
    private final KnowledgeBaseRowPurger purger;

    KnowledgebaseCourseDeletionGuard(KnowledgeBaseMapper knowledgeBases, KnowledgeBaseRowPurger purger) {
        this.knowledgeBases = knowledgeBases;
        this.purger = purger;
    }

    @Override
    public void beforeCourseDeleted(long courseId) {
        purger.purgeKnowledgeBases(knowledgeBases.selectList(new LambdaQueryWrapper<KnowledgeBaseEntity>()
                        .select(KnowledgeBaseEntity::getId)
                        .eq(KnowledgeBaseEntity::getCourseId, courseId))
                .stream().map(KnowledgeBaseEntity::getId).toList());
    }
}
