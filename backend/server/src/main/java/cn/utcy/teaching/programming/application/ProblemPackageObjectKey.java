package cn.utcy.teaching.programming.application;

import java.util.regex.Pattern;

final class ProblemPackageObjectKey {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private ProblemPackageObjectKey() {
    }

    static String of(long problemId, String sha256) {
        if (problemId < 1 || sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("题目包对象键参数不符合契约");
        }
        return "problem-packages/" + problemId + "/" + sha256 + ".zip";
    }
}
