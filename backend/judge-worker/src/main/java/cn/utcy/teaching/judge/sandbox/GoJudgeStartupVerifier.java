package cn.utcy.teaching.judge.sandbox;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GoJudgeStartupVerifier implements ApplicationRunner {

    static final String REQUIRED_VERSION = "v1.12.1";

    private final GoJudgeClient client;

    public GoJudgeStartupVerifier(GoJudgeClient client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        String version = client.version();
        if (!REQUIRED_VERSION.equals(version)) {
            throw new IllegalStateException(
                    "go-judge 版本必须为 " + REQUIRED_VERSION + "，实际为 " + version
            );
        }
        GoJudgeRequest.Command command = new GoJudgeRequest.Command(
                List.of("/bin/true"),
                List.of("PATH=/usr/bin:/bin", "LANG=C.UTF-8"),
                List.of(
                        GoJudgeRequest.CommandFile.content(""),
                        GoJudgeRequest.CommandFile.collector("stdout", 1024),
                        GoJudgeRequest.CommandFile.collector("stderr", 1024)
                ),
                1_000_000_000L,
                2_000_000_000L,
                32L * 1024 * 1024,
                32L * 1024 * 1024,
                1,
                Map.of(),
                List.of(),
                List.of(),
                1024,
                true
        );
        GoJudgeResponse response = client.run(new GoJudgeRequest(List.of(command)), Duration.ofSeconds(5));
        if (!"Accepted".equals(response.status()) || response.exitStatus() != 0) {
            throw new IllegalStateException("go-judge 启动自检未通过");
        }
    }
}
