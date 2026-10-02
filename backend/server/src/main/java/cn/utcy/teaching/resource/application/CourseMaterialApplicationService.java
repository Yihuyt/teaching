package cn.utcy.teaching.resource.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.course.CourseContentDeletionGuard;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import cn.utcy.teaching.shared.storage.ObjectStorageIntegrityVerifier;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.resource.domain.CourseMaterial;
import cn.utcy.teaching.resource.domain.MaterialKind;
import cn.utcy.teaching.resource.domain.MaterialState;
import cn.utcy.teaching.resource.infrastructure.CourseMaterialMapper;
import cn.utcy.teaching.resource.infrastructure.OssProperties;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.Duration;
import java.text.Collator;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CourseMaterialApplicationService {

    private static final Duration SIGNED_URL_LIFETIME = Duration.ofMinutes(10);

    private final CourseMaterialMapper materials;
    private final CurrentActor currentActor;
    private final CourseAccess courseAccess;
    private final OSS oss;
    private final OssProperties ossProperties;
    private final ObjectStorageIntegrityVerifier integrityVerifier;
    private final ObjectStorageDeletionQueue deletionQueue;
    private final CourseOutlineLinks courseOutlineLinks;
    private final List<CourseContentDeletionGuard> deletionGuards;

    public CourseMaterialApplicationService(
            CourseMaterialMapper materials,
            CurrentActor currentActor,
            CourseAccess courseAccess,
            OSS oss,
            OssProperties ossProperties,
            ObjectStorageIntegrityVerifier integrityVerifier,
            ObjectStorageDeletionQueue deletionQueue,
            CourseOutlineLinks courseOutlineLinks,
            List<CourseContentDeletionGuard> deletionGuards) {
        this.deletionGuards = List.copyOf(deletionGuards);
        this.materials = materials;
        this.currentActor = currentActor;
        this.courseAccess = courseAccess;
        this.oss = oss;
        this.ossProperties = ossProperties;
        this.integrityVerifier = integrityVerifier;
        this.deletionQueue = deletionQueue;
        this.courseOutlineLinks = courseOutlineLinks;
    }

    @Transactional(readOnly = true)
    public List<MaterialView> list(long courseId, Long parentId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        if (parentId != null) {
            CourseMaterial parent = requireMaterial(courseId, parentId);
            if (parent.getKind() != MaterialKind.FOLDER) {
                throw new ConflictException("父级课程资料必须是文件夹");
            }
        }
        LambdaQueryWrapper<CourseMaterial> query = new LambdaQueryWrapper<CourseMaterial>()
                .eq(CourseMaterial::getCourseId, courseId)
                .eq(parentId != null, CourseMaterial::getParentId, parentId)
                .isNull(parentId == null, CourseMaterial::getParentId);
        // 文件夹在前;同类内按中文拼音排序(数据库排序规则不懂拼音,故在服务层排)
        Collator collator = Collator.getInstance(Locale.CHINA);
        return materials.selectList(query).stream()
                .sorted(Comparator
                        .comparing((CourseMaterial material) -> material.getKind() != MaterialKind.FOLDER)
                        .thenComparing(CourseMaterial::getName, collator))
                .map(this::view)
                .toList();
    }

    @Transactional
    public MaterialView createFolder(long courseId, Long parentId, String name) {
        Actor actor = currentActor.require();
        courseAccess.requireManagementAccess(courseId, actor);
        requireParentForChildCreation(courseId, parentId);
        requireUniqueName(courseId, parentId, name.trim(), null);
        CourseMaterial material = CourseMaterial.folder(courseId, parentId, name.trim());
        requireMaterialMutation(materials.insert(material), "课程资料文件夹创建未生效");
        return view(material);
    }

    @Transactional
    public UploadTicket createUpload(
            long courseId,
            Long parentId,
            String name,
            String contentType,
            long sizeBytes,
            String sha256
    ) {
        Actor actor = currentActor.require();
        courseAccess.requireManagementAccess(courseId, actor);
        requireParentForChildCreation(courseId, parentId);
        requireUniqueName(courseId, parentId, name.trim(), null);
        String objectKey = "courses/%d/materials/%s".formatted(courseId, UUID.randomUUID());
        CourseMaterial material = CourseMaterial.pendingFile(
                courseId,
                parentId,
                name.trim(),
                objectKey,
                contentType.trim(),
                sizeBytes,
                sha256.toLowerCase());
        requireMaterialMutation(materials.insert(material), "课程资料上传记录创建未生效");

        Instant expiresAt = Instant.now().plus(SIGNED_URL_LIFETIME);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                ossProperties.bucket(), objectKey, HttpMethod.PUT);
        request.setExpiration(Date.from(expiresAt));
        request.setContentType(contentType.trim());
        request.setUserMetadata(Map.of("sha256", sha256.toLowerCase()));
        request.addHeader("x-oss-forbid-overwrite", "true");
        request.addAdditionalHeaderName("x-oss-forbid-overwrite");
        URL url = oss.generatePresignedUrl(request);
        return new UploadTicket(
                material.getId(),
                url.toString(),
                expiresAt,
                "PUT",
                Map.of(
                        "Content-Type", contentType.trim(),
                        "x-oss-meta-sha256", sha256.toLowerCase(),
                        "x-oss-forbid-overwrite", "true"));
    }

    @Transactional
    public MaterialView confirmUpload(long courseId, long materialId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        CourseMaterial material = requireMaterialForUpdate(courseId, materialId);
        if (material.getKind() != MaterialKind.FILE
                || material.getState() != MaterialState.PENDING_UPLOAD) {
            throw new ConflictException("该课程资料不处于待确认上传状态");
        }
        integrityVerifier.verify(
                ossProperties.bucket(),
                material.getObjectKey(),
                material.getSizeBytes(),
                material.getSha256());
        material.activate();
        requireMaterialMutation(
                materials.updateById(material),
                "课程资料上传状态已变化，确认未生效");
        return view(material);
    }

    /**
     * 供其他模块在**自行完成访问控制后**读取单个资料的元数据(名称、类型、状态):仅校验资料属于该课程。
     */
    @Transactional(readOnly = true)
    public MaterialView getTrusted(long courseId, long materialId) {
        return view(requireMaterial(courseId, materialId));
    }

    /**
     * 供其他模块在**自行完成访问控制后**取下载票:不做任何访问判定、不记学习事件(由调用方按自身语义记录)。
     */
    @Transactional(readOnly = true)
    public DownloadTicket createDownloadTrusted(long courseId, long materialId) {
        return ticket(requireMaterial(courseId, materialId));
    }

    private DownloadTicket ticket(CourseMaterial material) {
        if (material.getKind() != MaterialKind.FILE
                || material.getState() != MaterialState.ACTIVE) {
            throw new ConflictException("文件尚未上传完成");
        }
        Instant expiresAt = Instant.now().plus(SIGNED_URL_LIFETIME);
        URL url = oss.generatePresignedUrl(
                ossProperties.bucket(),
                material.getObjectKey(),
                Date.from(expiresAt),
                HttpMethod.GET);
        return new DownloadTicket(url.toString(), expiresAt);
    }

    @Transactional(readOnly = true)
    public DownloadTicket createDownload(long courseId, long materialId) {
        Actor actor = currentActor.require();
        courseAccess.requireLearningAccess(courseId, actor);
        if (!courseOutlineLinks.isLinked(courseId, CourseOutlineItemType.MATERIAL, materialId)) {
            throw new NotFoundException("课程内容中不存在该文件");
        }
        return ticket(requireMaterial(courseId, materialId));
    }

    @Transactional(readOnly = true)
    public DownloadTicket createDownloadForManagement(long courseId, long materialId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return ticket(requireMaterial(courseId, materialId));
    }

    @Transactional
    public MaterialView updateMetadata(long courseId, long materialId, String name) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        CourseMaterial material = requireMaterialForUpdate(courseId, materialId);
        requireUniqueName(courseId, material.getParentId(), name.trim(), materialId);
        material.rename(name.trim());
        requireMaterialMutation(
                materials.updateById(material),
                "课程资料状态已变化，名称更新未生效");
        return view(material);
    }

    /**
     * 删除是教师的明确决定,一律级联:文件夹连同其中全部内容;文件从课程内容移除、解除其他模块引用、
     * OSS 对象排入删除队列。同一事务内逐行加锁删除。
     */
    @Transactional
    public void delete(long courseId, List<Long> materialIds) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        for (Long materialId : materialIds) {
            deleteTree(courseId, requireMaterialForUpdate(courseId, materialId));
        }
    }

    private void deleteTree(long courseId, CourseMaterial material) {
        long materialId = material.getId();
        if (material.getKind() == MaterialKind.FOLDER) {
            for (CourseMaterial child : materials.selectList(new LambdaQueryWrapper<CourseMaterial>()
                    .eq(CourseMaterial::getCourseId, courseId)
                    .eq(CourseMaterial::getParentId, materialId))) {
                deleteTree(courseId, requireMaterialForUpdate(courseId, child.getId()));
            }
        } else {
            courseOutlineLinks.unlink(courseId, CourseOutlineItemType.MATERIAL, materialId);
            if (material.getObjectKey() != null) {
                deletionQueue.enqueue(ossProperties.bucket(), material.getObjectKey());
            }
            deletionGuards.forEach(guard -> guard.beforeContentDeleted(
                    courseId, CourseOutlineItemType.MATERIAL, materialId));
        }
        requireMaterialMutation(materials.deleteById(materialId), "课程资料状态已变化，删除未生效");
    }

    /** 文件夹非空即"有关联"(删除会连带其中内容);文件本身的关联由课程内容 / 图谱回答 */
    @Transactional(readOnly = true)
    public boolean folderHasContents(long courseId, long materialId) {
        CourseMaterial material = materials.selectInCourse(courseId, materialId);
        return material != null && material.getKind() == MaterialKind.FOLDER
                && materials.exists(new LambdaQueryWrapper<CourseMaterial>()
                .eq(CourseMaterial::getCourseId, courseId)
                .eq(CourseMaterial::getParentId, materialId));
    }

    /** 同一文件夹内文件 / 文件夹同名 → 409(数据库唯一键 uk_course_material_name 是并发兜底) */
    private void requireUniqueName(long courseId, Long parentId, String name, Long excludeId) {
        boolean taken = materials.exists(new LambdaQueryWrapper<CourseMaterial>()
                .eq(CourseMaterial::getCourseId, courseId)
                .eq(parentId != null, CourseMaterial::getParentId, parentId)
                .isNull(parentId == null, CourseMaterial::getParentId)
                .eq(CourseMaterial::getName, name)
                .ne(excludeId != null, CourseMaterial::getId, excludeId));
        if (taken) {
            throw new ConflictException("同一文件夹内已有同名文件或文件夹");
        }
    }

    private void requireParentForChildCreation(long courseId, Long parentId) {
        if (parentId == null) {
            return;
        }
        CourseMaterial parent = requireMaterialForUpdate(courseId, parentId);
        if (parent.getKind() != MaterialKind.FOLDER) {
            throw new ConflictException("父级课程资料必须是文件夹");
        }
    }

    private CourseMaterial requireMaterial(long courseId, long materialId) {
        CourseMaterial material = materials.selectInCourse(courseId, materialId);
        if (material == null) {
            throw new NotFoundException("课程资料不存在");
        }
        return material;
    }

    private CourseMaterial requireMaterialForUpdate(long courseId, long materialId) {
        CourseMaterial material = materials.selectForUpdate(courseId, materialId);
        if (material == null) {
            throw new NotFoundException("课程资料不存在");
        }
        return material;
    }

    private void requireMaterialMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }

    private MaterialView view(CourseMaterial material) {
        return new MaterialView(
                material.getId(),
                material.getCourseId(),
                material.getParentId(),
                material.getName(),
                material.getKind(),
                material.getContentType(),
                material.getSizeBytes(),
                material.getSha256(),
                material.getState(),
                material.getCreatedAt(),
                material.getUpdatedAt());
    }

    public record MaterialView(
            long id,
            long courseId,
            @Schema(nullable = true) Long parentId,
            String name,
            MaterialKind kind,
            @Schema(nullable = true) String contentType,
            @Schema(nullable = true) Long sizeBytes,
            @Schema(nullable = true) String sha256,
            MaterialState state,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record UploadTicket(
            long materialId,
            String url,
            Instant expiresAt,
            String method,
            Map<String, String> requiredHeaders
    ) {}

    public record DownloadTicket(String url, Instant expiresAt) {}
}
