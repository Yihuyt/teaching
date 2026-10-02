package cn.utcy.teaching.judge.packagefile;

import cn.utcy.teaching.judge.config.JudgeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TestcaseCacheJanitorTest {

    @Test
    void sweepsOnlyExpiredArchivesAndEmptyDirectories(@TempDir Path cacheRoot) throws Exception {
        Path staleDir = Files.createDirectories(cacheRoot.resolve("1"));
        Path stale = Files.writeString(staleDir.resolve("a".repeat(64) + ".zip"), "old");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofDays(8))));
        Path freshDir = Files.createDirectories(cacheRoot.resolve("2"));
        Path fresh = Files.writeString(freshDir.resolve("b".repeat(64) + ".zip"), "new");

        TestcaseCacheJanitor janitor = new TestcaseCacheJanitor(properties(cacheRoot));
        int removed = janitor.sweep(Instant.now().minus(Duration.ofDays(7)));

        assertThat(removed).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(staleDir).doesNotExist();
        assertThat(fresh).exists();
    }

    private static JudgeProperties properties(Path cacheRoot) {
        return new JudgeProperties(
                "judge.jobs",
                "judge.results",
                "judge-workers",
                "worker-1",
                URI.create("http://127.0.0.1:5050"),
                cacheRoot,
                Duration.ofMinutes(2),
                Duration.ofSeconds(20),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofMinutes(1),
                3,
                Duration.ofSeconds(15),
                Duration.ofSeconds(90),
                1,
                5,
                Duration.ofMinutes(5),
                Duration.ofDays(7)
        );
    }
}
