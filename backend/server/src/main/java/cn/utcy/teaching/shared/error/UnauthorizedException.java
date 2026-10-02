package cn.utcy.teaching.shared.error;

import org.springframework.http.HttpStatus;

public final class UnauthorizedException extends DomainException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
