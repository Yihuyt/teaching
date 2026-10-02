package cn.utcy.teaching.programming.infrastructure;

public final class ProblemPackageException extends RuntimeException {

    public ProblemPackageException(String message) {
        super(message);
    }

    public ProblemPackageException(String message, Throwable cause) {
        super(message, cause);
    }
}
