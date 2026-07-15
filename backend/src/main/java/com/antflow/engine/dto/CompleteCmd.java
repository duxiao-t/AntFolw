package com.antflow.engine.dto;

public record CompleteCmd(Long taskId, String action, String comment) {}
