package cn.utcy.teaching.shared.error;

import org.springframework.http.HttpStatus;

public final class BadRequestException extends DomainException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
