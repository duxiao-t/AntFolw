package com.antflow.engine;

public class NoAssigneeFoundException extends BizException {
    private final String nodeId;
    public NoAssigneeFoundException(String nodeId, String msg) {
        super("NO_ASSIGNEE", msg);
        this.nodeId = nodeId;
    }
    public String nodeId() {
        return nodeId;
    }
}
