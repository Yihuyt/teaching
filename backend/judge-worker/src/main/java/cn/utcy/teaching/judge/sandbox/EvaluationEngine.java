package cn.utcy.teaching.judge.sandbox;

import cn.utcy.teaching.judge.model.JudgeCaseResult;
import cn.utcy.teaching.judge.model.JudgeJob;
import cn.utcy.teaching.judge.model.JudgeLanguage;
import cn.utcy.teaching.judge.model.JudgeLimits;
import cn.utcy.teaching.judge.model.JudgeResult;
import cn.utcy.teaching.judge.model.JudgeStatus;
import cn.utcy.teaching.judgecontract.TestcasePackage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class EvaluationEngine {

    private static final long NANOS_PER_MILLISECOND = 1_000_000L;
    private static final long BYTES_PER_MEGABYTE = 1024L * 1024;
    private static final long BYTES_PER_KILOBYTE = 1024L;
    private static final long COMPILE_OUTPUT_LIMIT = 64L * 1024;
    private static final int DETAIL_LIMIT = 2000;
    private static final List<String> ENVIRONMENT = List.of("PATH=/usr/bin:/bin", "LANG=C.UTF-8");

    private final GoJudgeClient goJudgeClient;

    public EvaluationEngine(GoJudgeClient goJudgeClient) {
        this.goJudgeClient = goJudgeClient;
    }

    public JudgeResult evaluate(JudgeJob job, TestcasePackage testcasePackage, int attempt) {
        CompileOutcome compile;
        try {
            compile = compile(job);
        } catch (CompilationFailed exception) {
            return new JudgeResult(
                    1,
                    job.jobId(),
                    attempt,
                    job.submissionId(),
                    JudgeStatus.COMPILE_ERROR,
                    0,
                    0,
                    BigDecimal.ZERO,
                    exception.getMessage(),
                    List.of()
            );
        }

        List<JudgeCaseResult> caseResults = new ArrayList<>(testcasePackage.cases().size());
        JudgeStatus overall = JudgeStatus.ACCEPTED;
        int totalTimeMs = 0;
        int peakMemoryKb = 0;
        BigDecimal score = BigDecimal.ZERO;

        for (TestcasePackage.Testcase testcase : testcasePackage.cases()) {
            JudgeCaseResult result = runCase(job, compile, testcase);
            caseResults.add(result);
            totalTimeMs = Math.addExact(totalTimeMs, result.timeUsedMs());
            peakMemoryKb = Math.max(peakMemoryKb, result.memoryUsedKb());
            score = score.add(result.score());
            if (overall == JudgeStatus.ACCEPTED && result.status() != JudgeStatus.ACCEPTED) {
                overall = result.status();
            }
        }

        return new JudgeResult(
                1,
                job.jobId(),
                attempt,
                job.submissionId(),
                overall,
                totalTimeMs,
                peakMemoryKb,
                score,
                summary(overall),
                List.copyOf(caseResults)
        );
    }

    private CompileOutcome compile(JudgeJob job) {
        String sourceName;
        List<String> args;
        List<String> cachedOutput;
        if (job.language() == JudgeLanguage.C17) {
            sourceName = "main.c";
            args = List.of(
                    "/usr/bin/gcc-13", "-std=c17", "-O2", "-pipe", "-DONLINE_JUDGE",
                    sourceName, "-o", "main", "-lm"
            );
            cachedOutput = List.of("main");
        } else if (job.language() == JudgeLanguage.CPP20) {
            sourceName = "main.cpp";
            args = List.of(
                    "/usr/bin/g++-13", "-std=c++20", "-O2", "-pipe", "-DONLINE_JUDGE",
                    sourceName, "-o", "main"
            );
            cachedOutput = List.of("main");
        } else {
            sourceName = "main.py";
            args = List.of("/usr/bin/python3.12", "-I", "-m", "py_compile", sourceName);
            cachedOutput = List.of();
        }

        GoJudgeRequest.Command command = new GoJudgeRequest.Command(
                args,
                ENVIRONMENT,
                standardFiles("", COMPILE_OUTPUT_LIMIT),
                10_000L * NANOS_PER_MILLISECOND,
                20_000L * NANOS_PER_MILLISECOND,
                512L * BYTES_PER_MEGABYTE,
                512L * BYTES_PER_MEGABYTE,
                128,
                Map.of(sourceName, GoJudgeRequest.CommandFile.content(job.sourceCode())),
                List.of(),
                cachedOutput,
                COMPILE_OUTPUT_LIMIT,
                true
        );
        GoJudgeResponse response = goJudgeClient.run(
                new GoJudgeRequest(List.of(command)),
                Duration.ofSeconds(25)
        );
        if ("Internal Error".equals(response.status()) || "File Error".equals(response.status())) {
            throw new GoJudgeUnavailableException("go-judge 编译环境异常");
        }
        if (!"Accepted".equals(response.status()) || response.exitStatus() != 0) {
            throw new CompilationFailed(detail(response, "编译未通过"));
        }
        if (job.language() == JudgeLanguage.PYTHON312) {
            return new CompileOutcome(null);
        }
        String executableId = response.fileIds() == null ? null : response.fileIds().get("main");
        if (executableId == null || executableId.isBlank()) {
            throw new GoJudgeUnavailableException("go-judge 未返回编译产物");
        }
        return new CompileOutcome(executableId);
    }

    private JudgeCaseResult runCase(
            JudgeJob job,
            CompileOutcome compile,
            TestcasePackage.Testcase testcase
    ) {
        String input = decodeUtf8(testcase.input(), "测试输入");
        String expected = decodeUtf8(testcase.expectedOutput(), "标准输出");
        JudgeLimits limits = job.limits();
        Map<String, GoJudgeRequest.CommandFile> copyIn;
        List<String> args;
        if (job.language() == JudgeLanguage.PYTHON312) {
            copyIn = Map.of("main.py", GoJudgeRequest.CommandFile.content(job.sourceCode()));
            args = List.of("/usr/bin/python3.12", "-I", "main.py");
        } else {
            copyIn = Map.of("main", GoJudgeRequest.CommandFile.cached(compile.executableId()));
            args = List.of("./main");
        }

        long outputLimit = limits.outputLimitKb() * BYTES_PER_KILOBYTE;
        long cpuLimit = limits.timeLimitMs() * NANOS_PER_MILLISECOND;
        long clockLimit = Math.addExact(cpuLimit * 2, 2_000L * NANOS_PER_MILLISECOND);
        long memoryLimit = limits.memoryLimitMb() * BYTES_PER_MEGABYTE;
        GoJudgeRequest.Command command = new GoJudgeRequest.Command(
                args,
                ENVIRONMENT,
                standardFiles(input, outputLimit),
                cpuLimit,
                clockLimit,
                memoryLimit,
                memoryLimit,
                16,
                copyIn,
                List.of(),
                List.of(),
                outputLimit,
                true
        );
        Duration requestTimeout = Duration.ofMillis(clockLimit / NANOS_PER_MILLISECOND + 5000);
        GoJudgeResponse response = goJudgeClient.run(new GoJudgeRequest(List.of(command)), requestTimeout);

        JudgeStatus status = mapStatus(response);
        String detail = summary(status);
        if (status == JudgeStatus.ACCEPTED) {
            String actual = response.files() == null ? null : response.files().get("stdout");
            if (actual == null) {
                throw new GoJudgeUnavailableException("go-judge 未返回标准输出");
            }
            if (!normalizeOutput(expected).equals(normalizeOutput(actual))) {
                status = JudgeStatus.WRONG_ANSWER;
                detail = "输出与标准答案不一致";
            }
        } else if (status == JudgeStatus.RUNTIME_ERROR) {
            detail = detail(response, detail);
        }

        int timeMs = nanosToMilliseconds(response.time());
        int memoryKb = bytesToKilobytes(response.memory());
        BigDecimal caseScore = status == JudgeStatus.ACCEPTED ? testcase.score() : BigDecimal.ZERO;
        return new JudgeCaseResult(testcase.id(), status, timeMs, memoryKb, caseScore, detail);
    }

    private List<GoJudgeRequest.CommandFile> standardFiles(String input, long outputLimit) {
        return List.of(
                GoJudgeRequest.CommandFile.content(input),
                GoJudgeRequest.CommandFile.collector("stdout", outputLimit),
                GoJudgeRequest.CommandFile.collector("stderr", outputLimit)
        );
    }

    private JudgeStatus mapStatus(GoJudgeResponse response) {
        return switch (response.status()) {
            case "Accepted" -> response.exitStatus() == 0
                    ? JudgeStatus.ACCEPTED
                    : JudgeStatus.RUNTIME_ERROR;
            case "Time Limit Exceeded" -> JudgeStatus.TIME_LIMIT_EXCEEDED;
            case "Memory Limit Exceeded" -> JudgeStatus.MEMORY_LIMIT_EXCEEDED;
            case "Output Limit Exceeded" -> JudgeStatus.OUTPUT_LIMIT_EXCEEDED;
            case "Nonzero Exit Status", "Signalled", "Dangerous Syscall" -> JudgeStatus.RUNTIME_ERROR;
            case "Internal Error", "File Error" ->
                    throw new GoJudgeUnavailableException("go-judge 运行环境异常");
            default -> throw new GoJudgeUnavailableException("go-judge 返回未知状态：" + response.status());
        };
    }

    private String detail(GoJudgeResponse response, String defaultMessage) {
        String stderr = response.files() == null ? null : response.files().get("stderr");
        String message = stderr == null || stderr.isBlank() ? response.error() : stderr;
        if (message == null || message.isBlank()) {
            return defaultMessage;
        }
        String sanitized = message.replace("\u0000", "").strip();
        return sanitized.length() <= DETAIL_LIMIT ? sanitized : sanitized.substring(0, DETAIL_LIMIT);
    }

    private String decodeUtf8(byte[] value, String field) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new GoJudgeUnavailableException(field + "必须使用 UTF-8 编码", exception);
        }
    }

    static String normalizeOutput(String value) {
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].stripTrailing().isEmpty()) {
            end--;
        }
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < end; index++) {
            if (index > 0) {
                normalized.append('\n');
            }
            normalized.append(lines[index].stripTrailing());
        }
        return normalized.toString();
    }

    private int nanosToMilliseconds(long value) {
        long rounded = Math.ceilDiv(value, NANOS_PER_MILLISECOND);
        return Math.toIntExact(rounded);
    }

    private int bytesToKilobytes(long value) {
        return Math.toIntExact(Math.ceilDiv(value, BYTES_PER_KILOBYTE));
    }

    private String summary(JudgeStatus status) {
        return switch (status) {
            case ACCEPTED -> "评测通过";
            case WRONG_ANSWER -> "答案错误";
            case COMPILE_ERROR -> "编译错误";
            case RUNTIME_ERROR -> "运行错误";
            case TIME_LIMIT_EXCEEDED -> "超过时间限制";
            case MEMORY_LIMIT_EXCEEDED -> "超过内存限制";
            case OUTPUT_LIMIT_EXCEEDED -> "超过输出限制";
            case SYSTEM_ERROR -> "评测系统错误";
            case WORKER_CRASH_LIMIT -> "判题工作进程重试次数耗尽";
        };
    }

    private record CompileOutcome(String executableId) {
    }

    private static final class CompilationFailed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private CompilationFailed(String message) {
            super(message);
        }
    }
}
