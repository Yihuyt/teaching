package cn.utcy.teaching.resource.api;

import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService.DownloadTicket;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService.MaterialView;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService.UploadTicket;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/materials")
public class CourseMaterialController {

    private final CourseMaterialApplicationService materials;

    public CourseMaterialController(CourseMaterialApplicationService materials) {
        this.materials = materials;
    }

    @GetMapping
    public List<MaterialView> list(
            @PathVariable @Min(1) long courseId,
            @RequestParam(required = false) Long parentId
    ) {
        return materials.list(courseId, parentId);
    }

    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialView createFolder(
            @PathVariable @Min(1) long courseId,
            @Valid @RequestBody FolderRequest request
    ) {
        return materials.createFolder(courseId, request.parentId(), request.name());
    }

    @PostMapping("/upload-tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadTicket createUploadTicket(
            @PathVariable @Min(1) long courseId,
            @Valid @RequestBody UploadRequest request
    ) {
        return materials.createUpload(
                courseId,
                request.parentId(),
                request.name(),
                request.contentType(),
                request.sizeBytes(),
                request.sha256());
    }

    @PostMapping("/{materialId}/upload-confirmation")
    public MaterialView confirmUpload(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long materialId
    ) {
        return materials.confirmUpload(courseId, materialId);
    }

    @PostMapping("/{materialId}/download-tickets")
    public DownloadTicket createDownloadTicket(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long materialId
    ) {
        return materials.createDownload(courseId, materialId);
    }

    @PostMapping("/{materialId}/management/download-tickets")
    public DownloadTicket createDownloadTicketForManagement(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long materialId
    ) {
        return materials.createDownloadForManagement(courseId, materialId);
    }

    @PutMapping("/{materialId}")
    public MaterialView updateMetadata(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long materialId,
            @Valid @RequestBody UpdateMetadataRequest request
    ) {
        return materials.updateMetadata(courseId, materialId, request.name());
    }

    @DeleteMapping("/{materialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long materialId
    ) {
        materials.delete(courseId, List.of(materialId));
    }

    /** 批量删除(文件夹连同其中内容),一个事务内全成或全败 */
    @PostMapping("/deletions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMany(
            @PathVariable @Min(1) long courseId,
            @Valid @RequestBody MaterialDeletionRequest request
    ) {
        materials.delete(courseId, request.ids());
    }

    public record MaterialDeletionRequest(@NotEmpty List<@Min(1) Long> ids) {
    }

    public record FolderRequest(
            @Schema(nullable = true) Long parentId,
            @NotBlank @Size(max = 255) String name
    ) {}

    public record UploadRequest(
            @Schema(nullable = true) Long parentId,
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Size(max = 128) String contentType,
            @NotNull @Min(1) @Max(5_368_709_120L) Long sizeBytes,
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String sha256
    ) {}

    public record UpdateMetadataRequest(
            @NotBlank @Size(max = 255) String name
    ) {}
}
