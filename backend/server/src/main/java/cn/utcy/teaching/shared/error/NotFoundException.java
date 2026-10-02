package cn.utcy.teaching.shared.error;

import org.springframework.http.HttpStatus;

public final class NotFoundException extends DomainException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
