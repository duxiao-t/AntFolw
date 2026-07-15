package com.antflow.engine.handler;

import com.antflow.engine.BizException;

public class BadNodeTypeException extends BizException {
    public BadNodeTypeException(String m) {
        super("BAD_NODE_TYPE", m);
    }
}
