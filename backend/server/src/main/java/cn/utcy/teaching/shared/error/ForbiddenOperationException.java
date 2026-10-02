package cn.utcy.teaching.shared.error;

import org.springframework.http.HttpStatus;

public final class ForbiddenOperationException extends DomainException {

    public ForbiddenOperationException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
