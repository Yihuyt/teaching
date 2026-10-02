package cn.utcy.teaching.ai.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
class AiConfiguration {

    @Bean
    HttpClient aiHttpClient(DashScopeProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }
}
