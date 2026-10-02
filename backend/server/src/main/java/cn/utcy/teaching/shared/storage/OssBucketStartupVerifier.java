package cn.utcy.teaching.shared.storage;

import com.aliyun.oss.OSS;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
final class OssBucketStartupVerifier implements ApplicationRunner {

    private final OSS oss;
    private final Set<String> buckets;

    OssBucketStartupVerifier(
            OSS oss,
            List<ObjectStorageBucket> objectStorageBuckets
    ) {
        this.oss = oss;
        this.buckets = new LinkedHashSet<>(objectStorageBuckets.stream()
                .map(ObjectStorageBucket::bucket)
                .toList());
    }

    @Override
    public void run(ApplicationArguments arguments) {
        for (String bucket : buckets) {
            if (!oss.doesBucketExist(bucket)) {
                throw new IllegalStateException(
                        "OSS bucket 不存在或当前凭据无权访问：" + bucket);
            }
        }
    }
}
