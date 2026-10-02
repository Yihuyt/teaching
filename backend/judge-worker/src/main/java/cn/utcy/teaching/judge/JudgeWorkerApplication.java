package cn.utcy.teaching.judge;

import cn.utcy.teaching.judge.config.JudgeProperties;
import cn.utcy.teaching.judge.config.OssProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({JudgeProperties.class, OssProperties.class})
public class JudgeWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JudgeWorkerApplication.class, args);
    }
}
