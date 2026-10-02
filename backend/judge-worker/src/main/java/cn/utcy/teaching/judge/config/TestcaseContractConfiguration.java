package cn.utcy.teaching.judge.config;

import cn.utcy.teaching.judgecontract.TestcasePackageReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TestcaseContractConfiguration {

    @Bean
    TestcasePackageReader testcasePackageReader(ObjectMapper objectMapper) {
        return new TestcasePackageReader(objectMapper);
    }
}
