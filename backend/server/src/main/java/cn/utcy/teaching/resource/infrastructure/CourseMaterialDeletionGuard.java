package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import cn.utcy.teaching.resource.domain.CourseMaterial;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

@Component
class CourseMaterialDeletionGuard implements CourseDeletionGuard {

    private final CourseMaterialMapper materials;
    private final ObjectStorageDeletionQueue deletionQueue;
    private final OssProperties ossProperties;

    CourseMaterialDeletionGuard(
            CourseMaterialMapper materials,
            ObjectStorageDeletionQueue deletionQueue,
            OssProperties ossProperties
    ) {
        this.materials = materials;
        this.deletionQueue = deletionQueue;
        this.ossProperties = ossProperties;
    }

    @Override
    public void beforeCourseDeleted(long courseId) {
        materials.selectObjectKeysByCourse(courseId)
                .forEach(objectKey -> deletionQueue.enqueue(ossProperties.bucket(), objectKey));
        materials.delete(new LambdaQueryWrapper<CourseMaterial>().eq(CourseMaterial::getCourseId, courseId));
    }
}
