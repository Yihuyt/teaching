package cn.utcy.teaching.identity.application;

import cn.utcy.teaching.shared.error.DomainException;
import org.springframework.http.HttpStatus;

import java.time.Duration;

public final class TooManyLoginAttemptsException extends DomainException {

    private final Duration retryAfter;

    public TooManyLoginAttemptsException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多,请 " + describe(retryAfter) + "后再试");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    private static String describe(Duration duration) {
        long seconds = Math.max(1, duration.toSeconds());
        if (seconds < 60) {
            return seconds + " 秒";
        }
        long minutes = (seconds + 59) / 60;
        return minutes + " 分钟";
    }
}
