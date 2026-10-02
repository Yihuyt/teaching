package cn.utcy.teaching.judge.packagefile;

import cn.utcy.teaching.judge.config.OssProperties;
import com.aliyun.oss.OSS;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class OssStartupVerifier implements ApplicationRunner {

    private final OSS oss;
    private final OssProperties properties;

    public OssStartupVerifier(OSS oss, OssProperties properties) {
        this.oss = oss;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!oss.doesBucketExist(properties.testcaseBucket())) {
            throw new IllegalStateException("判题测试数据 OSS bucket 不存在或当前凭据无权访问");
        }
    }
}
