package com.antflow.authz;

public class HiddenResourceException extends RuntimeException {
    public HiddenResourceException(String message) {
        super(message);
    }
}
