package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.shared.storage.ObjectStorageBucket;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("teaching.testcase-oss")
public record TestcaseOssProperties(@NotBlank String bucket)
        implements ObjectStorageBucket {
}
