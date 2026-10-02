package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.course.CourseContentReferenceSource;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import org.springframework.stereotype.Component;

@Component
class MaterialReferenceSource implements CourseContentReferenceSource {

    private final CourseMaterialApplicationService materials;

    MaterialReferenceSource(CourseMaterialApplicationService materials) {
        this.materials = materials;
    }

    @Override
    public boolean references(long courseId, CourseOutlineItemType itemType, long contentId) {
        return itemType == CourseOutlineItemType.MATERIAL && materials.folderHasContents(courseId, contentId);
    }
}
