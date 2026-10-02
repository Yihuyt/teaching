package cn.utcy.teaching.resource.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import cn.utcy.teaching.shared.storage.ObjectStorageIntegrityVerifier;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.resource.domain.CourseMaterial;
import cn.utcy.teaching.resource.domain.MaterialKind;
import cn.utcy.teaching.resource.domain.MaterialState;
import cn.utcy.teaching.resource.infrastructure.CourseMaterialMapper;
import cn.utcy.teaching.resource.infrastructure.OssProperties;
import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseMaterialConcurrencyTest {

    @Test
    void confirmRenameAndDeleteAllLockMaterialRow() {
        Fixture fixture = new Fixture();
        when(fixture.materials.selectForUpdate(20L, 10L)).thenReturn(
                file(MaterialState.PENDING_UPLOAD),
                file(MaterialState.ACTIVE),
                file(MaterialState.ACTIVE));
        when(fixture.materials.updateById((CourseMaterial) any())).thenReturn(1);
        when(fixture.materials.deleteById(10L)).thenReturn(1);

        fixture.service.confirmUpload(20L, 10L);
        var updated = fixture.service.updateMetadata(20L, 10L, "新名称");
        fixture.service.delete(20L, List.of(10L));

        assertThat(updated.name()).isEqualTo("新名称");
        assertThat(updated.courseId()).isEqualTo(20L);
        verify(fixture.materials, times(3)).selectForUpdate(20L, 10L);
        verify(fixture.materials, never()).selectInCourse(20L, 10L);
        verify(fixture.courseAccess, times(3)).requireManagementAccess(20L, fixture.actor);
        verify(fixture.integrityVerifier).verify(
                "materials",
                "courses/20/materials/object",
                128,
                "a".repeat(64));
        verify(fixture.deletionQueue).enqueue(
                "materials",
                "courses/20/materials/object");
    }

    @Test
    void zeroAffectedMetadataUpdateIsExplicitConflict() {
        Fixture fixture = new Fixture();
        when(fixture.materials.selectForUpdate(20L, 10L)).thenReturn(file(MaterialState.ACTIVE));
        when(fixture.materials.updateById((CourseMaterial) any())).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.updateMetadata(20L, 10L, "新名称"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("课程资料状态已变化，名称更新未生效");
    }

    @Test
    void zeroAffectedDeleteCannotReturnSuccess() {
        Fixture fixture = new Fixture();
        when(fixture.materials.selectForUpdate(20L, 10L)).thenReturn(file(MaterialState.ACTIVE));
        when(fixture.materials.deleteById(10L)).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.delete(20L, List.of(10L)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("课程资料状态已变化，删除未生效");
    }

    @Test
    void batchDeleteLocksAndDeletesEveryRequestedMaterial() {
        Fixture fixture = new Fixture();
        when(fixture.materials.selectForUpdate(20L, 10L))
                .thenReturn(file(10L, MaterialState.ACTIVE, "courses/20/materials/object"));
        when(fixture.materials.selectForUpdate(20L, 11L))
                .thenReturn(file(11L, MaterialState.ACTIVE, "courses/20/materials/other"));
        when(fixture.materials.deleteById(10L)).thenReturn(1);
        when(fixture.materials.deleteById(11L)).thenReturn(1);

        fixture.service.delete(20L, List.of(10L, 11L));

        verify(fixture.materials).selectForUpdate(20L, 10L);
        verify(fixture.materials).selectForUpdate(20L, 11L);
        verify(fixture.materials).deleteById(10L);
        verify(fixture.materials).deleteById(11L);
        verify(fixture.deletionQueue).enqueue("materials", "courses/20/materials/object");
        verify(fixture.deletionQueue).enqueue("materials", "courses/20/materials/other");
        verify(fixture.courseAccess, times(1)).requireManagementAccess(20L, fixture.actor);
    }

    @Test
    void zeroAffectedFolderInsertIsExplicitConflict() {
        Fixture fixture = new Fixture();
        when(fixture.materials.insert((CourseMaterial) any())).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.createFolder(20L, null, "资料"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("课程资料文件夹创建未生效");
    }

    @Test
    void zeroAffectedUploadRecordInsertIsExplicitConflict() {
        Fixture fixture = new Fixture();
        when(fixture.materials.insert((CourseMaterial) any())).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.createUpload(
                20L,
                null,
                "资料.pdf",
                "application/pdf",
                128,
                "a".repeat(64)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("课程资料上传记录创建未生效");
    }

    private static CourseMaterial file(MaterialState state) {
        return file(10L, state, "courses/20/materials/object");
    }

    private static CourseMaterial file(long id, MaterialState state, String objectKey) {
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        return new CourseMaterial(
                id,
                20L,
                null,
                "课程资料 " + id,
                MaterialKind.FILE,
                objectKey,
                "application/pdf",
                128L,
                "a".repeat(64),
                state,
                now,
                now);
    }

    private static final class Fixture {
        private final Actor actor = new Actor(7L, "teacher", SystemRole.TEACHER);
        private final CourseMaterialMapper materials = mock(CourseMaterialMapper.class);
        private final CurrentActor currentActor = mock(CurrentActor.class);
        private final CourseAccess courseAccess = mock(CourseAccess.class);
        private final OSS oss = mock(OSS.class);
        private final ObjectStorageIntegrityVerifier integrityVerifier =
                mock(ObjectStorageIntegrityVerifier.class);
        private final ObjectStorageDeletionQueue deletionQueue =
                mock(ObjectStorageDeletionQueue.class);
        private final CourseOutlineLinks courseOutlineLinks = mock(CourseOutlineLinks.class);
        private final CourseMaterialApplicationService service;

        private Fixture() {
            when(currentActor.require()).thenReturn(actor);
            service = new CourseMaterialApplicationService(
                    materials,
                    currentActor,
                    courseAccess,
                    oss,
                    new OssProperties(
                            "https://oss-cn-hangzhou.aliyuncs.com",
                            "materials",
                            Path.of("/run/secrets/oss")),
                    integrityVerifier,
                    deletionQueue,
                    courseOutlineLinks,
                    List.of());
        }
    }
}
