package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.judgecontract.TestcasePackageReader;
import cn.utcy.teaching.judgecontract.TestcasePackageWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TestcaseContractConfiguration {

    @Bean
    TestcasePackageReader testcasePackageReader(ObjectMapper objectMapper) {
        return new TestcasePackageReader(objectMapper);
    }

    @Bean
    TestcasePackageWriter testcasePackageWriter(ObjectMapper objectMapper) {
        return new TestcasePackageWriter(objectMapper);
    }
}
