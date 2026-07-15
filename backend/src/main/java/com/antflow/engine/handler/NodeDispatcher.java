package com.antflow.engine.handler;

import org.springframework.stereotype.Component;

@Component
public class NodeDispatcher {

    public void assertKnown(String type) {
        if ("start".equals(type) || "approval".equals(type) || "end".equals(type)) {
            return;
        }
        throw new BadNodeTypeException("unknown node type: " + type);
    }
}
