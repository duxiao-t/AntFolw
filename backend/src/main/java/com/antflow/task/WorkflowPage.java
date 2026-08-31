package com.antflow.task;

import java.util.List;

public record WorkflowPage<T>(List<T> records, long total, int page, int size) { }
