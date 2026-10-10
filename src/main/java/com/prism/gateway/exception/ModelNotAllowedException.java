package com.prism.gateway.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class ModelNotAllowedException extends RuntimeException {
    public ModelNotAllowedException(String message) {
        super(message);
    }
}
