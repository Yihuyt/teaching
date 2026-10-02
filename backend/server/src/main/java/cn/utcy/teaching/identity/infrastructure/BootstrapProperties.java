package cn.utcy.teaching.identity.infrastructure;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

@Validated
@ConfigurationProperties("teaching.bootstrap")
public record BootstrapProperties(@NotNull Path rootPasswordPath) {
}
