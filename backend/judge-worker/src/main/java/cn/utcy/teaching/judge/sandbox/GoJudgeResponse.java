package cn.utcy.teaching.judge.sandbox;

import java.util.List;
import java.util.Map;

public record GoJudgeResponse(
        String status,
        int exitStatus,
        String error,
        long time,
        long memory,
        long runTime,
        long procPeak,
        Map<String, String> files,
        Map<String, String> fileIds,
        List<FileError> fileError
) {

    public record FileError(String name, String type, String message) {
    }
}
