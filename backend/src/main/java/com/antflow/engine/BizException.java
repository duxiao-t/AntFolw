package com.antflow.engine;

public class BizException extends RuntimeException {
    private final String code;
    public BizException(String code, String msg) {
        super(msg);
        this.code = code;
    }
    public String getCode() {
        return code;
    }
}
