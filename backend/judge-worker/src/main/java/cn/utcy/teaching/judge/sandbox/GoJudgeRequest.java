package cn.utcy.teaching.judge.sandbox;

import java.util.List;
import java.util.Map;

public record GoJudgeRequest(List<Command> cmd) {

    public record Command(
            List<String> args,
            List<String> env,
            List<CommandFile> files,
            long cpuLimit,
            long clockLimit,
            long memoryLimit,
            long stackLimit,
            long procLimit,
            Map<String, CommandFile> copyIn,
            List<String> copyOut,
            List<String> copyOutCached,
            long copyOutMax,
            boolean strictMemoryLimit
    ) {
    }

    public record CommandFile(
            String content,
            String fileId,
            String name,
            Long max
    ) {

        public static CommandFile content(String content) {
            return new CommandFile(content, null, null, null);
        }

        public static CommandFile cached(String fileId) {
            return new CommandFile(null, fileId, null, null);
        }

        public static CommandFile collector(String name, long max) {
            return new CommandFile(null, null, name, max);
        }
    }
}
