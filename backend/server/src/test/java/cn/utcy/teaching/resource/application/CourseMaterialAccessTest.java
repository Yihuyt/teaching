package cn.utcy.teaching.resource.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseContentDeletionGuard;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import cn.utcy.teaching.shared.storage.ObjectStorageIntegrityVerifier;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.resource.domain.CourseMaterial;
import cn.utcy.teaching.resource.domain.MaterialKind;
import cn.utcy.teaching.resource.domain.MaterialState;
import cn.utcy.teaching.resource.infrastructure.CourseMaterialMapper;
import cn.utcy.teaching.resource.infrastructure.OssProperties;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseMaterialAccessTest {

    @Test
    void emptyFolderReturnsAnEmptyList() {
        Fixture fixture = new Fixture(new Actor(7L, "teacher", SystemRole.TEACHER));
        long folderId = 10L;
        when(fixture.materials.selectInCourse(30L, folderId)).thenReturn(folder(30L, folderId, null));
        when(fixture.materials.selectList(any())).thenReturn(List.of());

        assertThat(fixture.service.list(30L, folderId)).isEmpty();

        verify(fixture.courseAccess).requireManagementAccess(30L, fixture.actor);
        verify(fixture.materials).selectInCourse(30L, folderId);
        verify(fixture.materials).selectList(any());
    }

    @Test
    void folderListingRejectsParentFromAnotherCourse() {
        Fixture fixture = new Fixture(new Actor(7L, "teacher", SystemRole.TEACHER));

        assertThatThrownBy(() -> fixture.service.list(30L, 10L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("课程资料不存在");

        verify(fixture.courseAccess).requireManagementAccess(30L, fixture.actor);
        verify(fixture.materials).selectInCourse(30L, 10L);
        verify(fixture.materials, never()).selectList(any());
    }

    @Test
    void courseMemberCannotDownloadUnlinkedMaterial() {
        Fixture fixture = new Fixture(new Actor(9L, "student", SystemRole.STUDENT));
        when(fixture.materials.selectInCourse(20L, 10L)).thenReturn(activeFile());
        when(fixture.outlineLinks.isLinked(20L, CourseOutlineItemType.MATERIAL, 10L))
                .thenReturn(false);

        assertThatThrownBy(() -> fixture.service.createDownload(20L, 10L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("课程内容中不存在该文件");

        verify(fixture.courseAccess).requireLearningAccess(20L, fixture.actor);
        verify(fixture.outlineLinks).isLinked(20L, CourseOutlineItemType.MATERIAL, 10L);
        verify(fixture.courseAccess, never()).requireManagementAccess(20L, fixture.actor);
    }

    @Test
    void courseMemberCanDownloadLinkedMaterial() throws MalformedURLException {
        Fixture fixture = new Fixture(new Actor(9L, "student", SystemRole.STUDENT));
        when(fixture.materials.selectInCourse(20L, 10L)).thenReturn(activeFile());
        when(fixture.outlineLinks.isLinked(20L, CourseOutlineItemType.MATERIAL, 10L))
                .thenReturn(true);
        when(fixture.oss.generatePresignedUrl(
                eq("materials"),
                eq("courses/20/materials/object"),
                any(Date.class),
                eq(HttpMethod.GET)))
                .thenReturn(URI.create("https://example.test/material").toURL());

        var ticket = fixture.service.createDownload(20L, 10L);

        assertThat(ticket.url()).isEqualTo("https://example.test/material");
        verify(fixture.courseAccess).requireLearningAccess(20L, fixture.actor);
        verify(fixture.courseAccess, never()).requireManagementAccess(20L, fixture.actor);
    }

    @Test
    void courseManagerCanDownloadUnlinkedMaterial() throws MalformedURLException {
        Fixture fixture = new Fixture(new Actor(7L, "teacher", SystemRole.TEACHER));
        when(fixture.materials.selectInCourse(20L, 10L)).thenReturn(activeFile());
        when(fixture.outlineLinks.isLinked(20L, CourseOutlineItemType.MATERIAL, 10L))
                .thenReturn(false);
        when(fixture.oss.generatePresignedUrl(
                eq("materials"),
                eq("courses/20/materials/object"),
                any(Date.class),
                eq(HttpMethod.GET)))
                .thenReturn(URI.create("https://example.test/material").toURL());

        var ticket = fixture.service.createDownloadForManagement(20L, 10L);

        assertThat(ticket.url()).isEqualTo("https://example.test/material");
        verify(fixture.courseAccess).requireManagementAccess(20L, fixture.actor);
        verify(fixture.courseAccess, never()).requireLearningAccess(20L, fixture.actor);
        verify(fixture.outlineLinks, never())
                .isLinked(20L, CourseOutlineItemType.MATERIAL, 10L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateNameInSameFolderIsRejected() {
        Fixture fixture = new Fixture(new Actor(7L, "teacher", SystemRole.TEACHER));
        when(fixture.materials.exists(any(LambdaQueryWrapper.class))).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.createFolder(20L, null, "课程资料"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("同一文件夹内已有同名文件或文件夹");

        verify(fixture.courseAccess).requireManagementAccess(20L, fixture.actor);
        verify(fixture.materials, never()).insert(any(CourseMaterial.class));
    }

    @Test
    void deletingFileUnlinksEnqueuesObjectAndNotifiesGuardsBeforeRowDeletion() {
        Fixture fixture = new Fixture(new Actor(7L, "teacher", SystemRole.TEACHER));
        when(fixture.materials.selectForUpdate(20L, 10L)).thenReturn(activeFile());
        when(fixture.materials.deleteById(10L)).thenReturn(1);

        fixture.service.delete(20L, List.of(10L));

        InOrder order = inOrder(
                fixture.outlineLinks, fixture.deletionQueue, fixture.deletionGuard, fixture.materials);
        order.verify(fixture.outlineLinks).unlink(20L, CourseOutlineItemType.MATERIAL, 10L);
        order.verify(fixture.deletionQueue).enqueue("materials", "courses/20/materials/object");
        order.verify(fixture.deletionGuard)
                .beforeContentDeleted(20L, CourseOutlineItemType.MATERIAL, 10L);
        order.verify(fixture.materials).deleteById(10L);
        verify(fixture.courseAccess).requireManagementAccess(20L, fixture.actor);
        verify(fixture.outlineLinks, never()).isLinked(anyLong(), any(), anyLong());
    }

    @Test
    void deletingFolderCascadesToEveryDescendantFile() {
        Fixture fixture = new Fixture(new Actor(7L, "teacher", SystemRole.TEACHER));
        CourseMaterial folder = folder(20L, 10L, null);
        CourseMaterial file = file(11L, 10L, "courses/20/materials/first");
        CourseMaterial subfolder = folder(20L, 12L, 10L);
        CourseMaterial nested = file(13L, 12L, "courses/20/materials/second");
        when(fixture.materials.selectForUpdate(20L, 10L)).thenReturn(folder);
        when(fixture.materials.selectForUpdate(20L, 11L)).thenReturn(file);
        when(fixture.materials.selectForUpdate(20L, 12L)).thenReturn(subfolder);
        when(fixture.materials.selectForUpdate(20L, 13L)).thenReturn(nested);
        when(fixture.materials.selectList(any()))
                .thenReturn(List.of(file, subfolder))
                .thenReturn(List.of(nested));
        when(fixture.materials.deleteById(anyLong())).thenReturn(1);

        fixture.service.delete(20L, List.of(10L));

        InOrder order = inOrder(fixture.materials);
        order.verify(fixture.materials).deleteById(11L);
        order.verify(fixture.materials).deleteById(13L);
        order.verify(fixture.materials).deleteById(12L);
        order.verify(fixture.materials).deleteById(10L);
        verify(fixture.deletionQueue).enqueue("materials", "courses/20/materials/first");
        verify(fixture.deletionQueue).enqueue("materials", "courses/20/materials/second");
        verify(fixture.outlineLinks).unlink(20L, CourseOutlineItemType.MATERIAL, 11L);
        verify(fixture.outlineLinks).unlink(20L, CourseOutlineItemType.MATERIAL, 13L);
        verify(fixture.outlineLinks, never()).unlink(eq(20L), any(), eq(10L));
        verify(fixture.outlineLinks, never()).unlink(eq(20L), any(), eq(12L));
        verify(fixture.deletionGuard, never())
                .beforeContentDeleted(eq(20L), any(), eq(10L));
    }

    private static CourseMaterial activeFile() {
        return file(10L, 5L, "courses/20/materials/object");
    }

    private static CourseMaterial file(long id, Long parentId, String objectKey) {
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        return new CourseMaterial(
                id,
                20L,
                parentId,
                "课程资料 " + id,
                MaterialKind.FILE,
                objectKey,
                "application/pdf",
                128L,
                "a".repeat(64),
                MaterialState.ACTIVE,
                now,
                now);
    }

    private static CourseMaterial folder(long courseId, long id, Long parentId) {
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        return new CourseMaterial(
                id,
                courseId,
                parentId,
                "目录 " + id,
                MaterialKind.FOLDER,
                null,
                null,
                null,
                null,
                MaterialState.ACTIVE,
                now,
                now);
    }

    private static final class Fixture {
        private final Actor actor;
        private final CourseMaterialMapper materials = mock(CourseMaterialMapper.class);
        private final CurrentActor currentActor = mock(CurrentActor.class);
        private final CourseAccess courseAccess = mock(CourseAccess.class);
        private final OSS oss = mock(OSS.class);
        private final CourseOutlineLinks outlineLinks = mock(CourseOutlineLinks.class);
        private final ObjectStorageDeletionQueue deletionQueue = mock(ObjectStorageDeletionQueue.class);
        private final CourseContentDeletionGuard deletionGuard = mock(CourseContentDeletionGuard.class);
        private final CourseMaterialApplicationService service;

        private Fixture(Actor actor) {
            this.actor = actor;
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
                    mock(ObjectStorageIntegrityVerifier.class),
                    deletionQueue,
                    outlineLinks,
                    List.of(deletionGuard));
        }
    }
}
