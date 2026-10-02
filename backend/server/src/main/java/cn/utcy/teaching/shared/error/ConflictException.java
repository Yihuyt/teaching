package cn.utcy.teaching.shared.error;

import org.springframework.http.HttpStatus;

public final class ConflictException extends DomainException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
