package cn.utcy.teaching.judgecontract;

public class TestcasePackageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TestcasePackageException(String message) {
        super(message);
    }

    public TestcasePackageException(String message, Throwable cause) {
        super(message, cause);
    }
}
