package com.antflow.engine.dto;

import java.util.List;
import java.util.Map;

public record StartCmd(String formCode, Object data,
                       Map<String, List<Long>> selfSelected) {}
