package cn.utcy.teaching.judge.packagefile;

import cn.utcy.teaching.judge.config.JudgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

/**
 * 测试包缓存回收:按最近使用时间(load 命中即触摸 mtime)删除超过保留期的包与空目录。
 * 缓存卷只增不减曾是"写满即全站系统错误"的隐患;保留期远大于单任务墙钟,在用包不会被误删。
 */
@Component
class TestcaseCacheJanitor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TestcaseCacheJanitor.class);

    private final JudgeProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("testcase-cache-janitor").factory());

    TestcaseCacheJanitor(JudgeProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        scheduler.scheduleWithFixedDelay(this::sweepSafely, 0, 1, TimeUnit.HOURS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void sweepSafely() {
        try {
            int removed = sweep(Instant.now().minus(properties.testcaseCacheRetention()));
            if (removed > 0) {
                log.info("测试包缓存回收完成，删除 {} 个过期包", removed);
            }
        } catch (RuntimeException | IOException exception) {
            log.error("测试包缓存回收失败，下一轮重试", exception);
        }
    }

    int sweep(Instant threshold) throws IOException {
        Path cacheRoot = properties.testcaseCache().toAbsolutePath().normalize();
        if (!Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int removed = 0;
        try (DirectoryStream<Path> problemDirs = Files.newDirectoryStream(cacheRoot)) {
            for (Path problemDir : problemDirs) {
                if (!Files.isDirectory(problemDir, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                removed += sweepProblemDir(problemDir, threshold);
                try (DirectoryStream<Path> remaining = Files.newDirectoryStream(problemDir)) {
                    if (!remaining.iterator().hasNext()) {
                        Files.deleteIfExists(problemDir);
                    }
                }
            }
        }
        return removed;
    }

    private int sweepProblemDir(Path problemDir, Instant threshold) throws IOException {
        int removed = 0;
        try (DirectoryStream<Path> archives = Files.newDirectoryStream(problemDir, "*.zip")) {
            for (Path archive : archives) {
                if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.getLastModifiedTime(archive).toInstant().isBefore(threshold)) {
                    Files.deleteIfExists(archive);
                    removed += 1;
                }
            }
        }
        return removed;
    }
}
