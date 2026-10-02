package cn.utcy.teaching.courseware.infrastructure;

import cn.utcy.teaching.shared.storage.ObjectStorageBucket;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("teaching.courseware.oss")
public record CoursewareOssProperties(@NotBlank String bucket)
        implements ObjectStorageBucket {
}
