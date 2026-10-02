package cn.utcy.teaching.tutor.infrastructure;

import cn.utcy.teaching.shared.course.KnowledgeBaseDeletionGuard;
import org.springframework.stereotype.Component;

@Component
class TutorMountDeletionGuard implements KnowledgeBaseDeletionGuard {

    private final TutorAssistantMapper assistants;

    TutorMountDeletionGuard(TutorAssistantMapper assistants) {
        this.assistants = assistants;
    }

    @Override
    public void beforeKnowledgeBaseDeleted(long knowledgeBaseId) {
        assistants.unmountKnowledgeBase(knowledgeBaseId);
    }
}
