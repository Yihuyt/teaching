package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.storage.ObjectStorageBucket;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@Validated
@ConfigurationProperties("teaching.oss")
public record OssProperties(
        @NotBlank String endpoint,
        @NotBlank String bucket,
        @NotNull Path credentialsPath
) implements ObjectStorageBucket {
}
