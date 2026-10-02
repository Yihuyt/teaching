package cn.utcy.teaching.judge.config;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
public class JudgeConfiguration {

    @Bean(destroyMethod = "shutdown")
    OSS testcaseOssClient(OssProperties properties) {
        OssCredentials credentials = OssCredentialsReader.read(properties.credentialsPath());
        // 下载无超时曾是"评测线程卡死但健康恒 UP"的最可能成因,必须有界失败
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setConnectionTimeout(5_000);
        configuration.setSocketTimeout(30_000);
        configuration.setRequestTimeout(120_000);
        configuration.setRequestTimeoutEnabled(true);
        configuration.setMaxErrorRetry(1);
        return new OSSClientBuilder().build(
                properties.endpoint(),
                credentials.accessKeyId(),
                credentials.accessKeySecret(),
                configuration
        );
    }

    @Bean
    ObjectMapper strictObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Bean
    HttpClient goJudgeHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
