package cn.utcy.teaching.shared.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 本机开发时定位仓库根目录:从工作目录向上找到含 deploy/secrets 的那一层,记为 teaching.repo-root,
 * 配置文件里的密钥目录与 .env 默认路径都以它为基准,因此 IDEA 从任何模块目录启动都能读到。
 * 容器里由 compose 给出绝对路径,这里找不到也不设值。
 */
public class RepositoryRootEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY = "teaching.repo-root";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isDirectory(directory.resolve("deploy").resolve("secrets"))) {
                environment.getPropertySources().addLast(
                        new MapPropertySource("repositoryRoot", Map.of(PROPERTY, directory.toString())));
                return;
            }
            directory = directory.getParent();
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }
}
