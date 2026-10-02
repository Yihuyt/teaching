package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseMaterialDeletionGuardTest {

    @Test
    void courseDeletionEnqueuesEveryMaterialObjectBeforeRowsAreCascaded() {
        CourseMaterialMapper materials = mock(CourseMaterialMapper.class);
        ObjectStorageDeletionQueue deletionQueue = mock(ObjectStorageDeletionQueue.class);
        when(materials.selectObjectKeysByCourse(20L)).thenReturn(List.of(
                "courses/20/materials/first",
                "courses/20/materials/second"));
        CourseMaterialDeletionGuard guard = new CourseMaterialDeletionGuard(
                materials,
                deletionQueue,
                new OssProperties(
                        "https://oss-cn-hangzhou.aliyuncs.com",
                        "materials",
                        Path.of("/run/secrets/oss")));

        guard.beforeCourseDeleted(20L);

        verify(materials).selectObjectKeysByCourse(20L);
        var ordered = inOrder(deletionQueue);
        ordered.verify(deletionQueue).enqueue(
                "materials",
                "courses/20/materials/first");
        ordered.verify(deletionQueue).enqueue(
                "materials",
                "courses/20/materials/second");
    }
}
