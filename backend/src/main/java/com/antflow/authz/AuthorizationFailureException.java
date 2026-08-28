package com.antflow.authz;

import org.springframework.security.access.AccessDeniedException;

public class AuthorizationFailureException extends AccessDeniedException {
    private final String code;

    public AuthorizationFailureException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
