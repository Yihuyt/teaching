package cn.utcy.teaching.judge.sandbox;

public class GoJudgeUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GoJudgeUnavailableException(String message) {
        super(message);
    }

    public GoJudgeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
