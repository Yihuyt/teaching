package cn.utcy.teaching.programming.application;

final class TestcaseObjectKey {

    private TestcaseObjectKey() {
    }

    static String of(long problemId, String sha256) {
        return "judge-testcases/" + problemId + "/" + sha256 + ".zip";
    }
}
