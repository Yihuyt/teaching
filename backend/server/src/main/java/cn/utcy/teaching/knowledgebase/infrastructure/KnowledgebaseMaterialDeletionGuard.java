package cn.utcy.teaching.knowledgebase.infrastructure;

import cn.utcy.teaching.shared.course.CourseContentDeletionGuard;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import org.springframework.stereotype.Component;

@Component
class KnowledgebaseMaterialDeletionGuard implements CourseContentDeletionGuard {

    private final KbDocumentMapper documents;

    KnowledgebaseMaterialDeletionGuard(KbDocumentMapper documents) {
        this.documents = documents;
    }

    @Override
    public void beforeContentDeleted(long courseId, CourseOutlineItemType itemType, long contentId) {
        if (itemType == CourseOutlineItemType.MATERIAL) {
            documents.detachMaterial(contentId);
        }
    }
}
