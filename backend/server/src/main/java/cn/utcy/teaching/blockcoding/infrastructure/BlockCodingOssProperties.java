package cn.utcy.teaching.blockcoding.infrastructure;

import cn.utcy.teaching.shared.storage.ObjectStorageBucket;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("teaching.blockcoding.oss")
public record BlockCodingOssProperties(@NotBlank String bucket)
        implements ObjectStorageBucket {
}
