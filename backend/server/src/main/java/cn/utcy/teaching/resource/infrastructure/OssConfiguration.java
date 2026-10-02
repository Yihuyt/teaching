package cn.utcy.teaching.resource.infrastructure;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

@Configuration
class OssConfiguration {

    private static final String ACCESS_KEY_ID = "ALIYUN_OSS_ACCESS_KEY_ID=";
    private static final String ACCESS_KEY_SECRET = "ALIYUN_OSS_ACCESS_KEY_SECRET=";

    @Bean(destroyMethod = "shutdown")
    OSS ossClient(OssProperties properties) throws IOException {
        Credentials credentials = readCredentials(properties.credentialsPath());
        return new OSSClientBuilder().build(
                properties.endpoint(),
                credentials.accessKeyId(),
                credentials.accessKeySecret(),
                clientConfiguration());
    }

    /** 网络故障必须在有界时间内失败:OSS 慢调用曾拖停共享调度线程上的判题投递 */
    static ClientBuilderConfiguration clientConfiguration() {
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setConnectionTimeout(5_000);
        configuration.setSocketTimeout(30_000);
        configuration.setRequestTimeout(60_000);
        configuration.setRequestTimeoutEnabled(true);
        configuration.setMaxErrorRetry(1);
        return configuration;
    }

    static Credentials readCredentials(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("OSS 密钥路径必须是普通文件且不能是符号链接");
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != 2
                || !lines.get(0).startsWith(ACCESS_KEY_ID)
                || !lines.get(1).startsWith(ACCESS_KEY_SECRET)) {
            throw new IllegalStateException(
                    "OSS 密钥文件必须严格包含 ALIYUN_OSS_ACCESS_KEY_ID 和 ALIYUN_OSS_ACCESS_KEY_SECRET 两行");
        }
        String accessKeyId = lines.get(0).substring(ACCESS_KEY_ID.length());
        String accessKeySecret = lines.get(1).substring(ACCESS_KEY_SECRET.length());
        if (accessKeyId.isBlank()
                || accessKeySecret.isBlank()
                || !accessKeyId.equals(accessKeyId.trim())
                || !accessKeySecret.equals(accessKeySecret.trim())) {
            throw new IllegalStateException(
                    "OSS AccessKey ID 与 AccessKey Secret 均不能为空且首尾不能包含空白");
        }
        return new Credentials(accessKeyId, accessKeySecret);
    }

    record Credentials(String accessKeyId, String accessKeySecret) {
    }
}
