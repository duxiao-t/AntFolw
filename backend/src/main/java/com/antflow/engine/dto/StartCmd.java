package com.antflow.engine.dto;

import java.util.List;
import java.util.Map;

/**
 * 发起流程的指令。
 *
 * <p>单公司改造后不再携带公司归属：公司不是业务维度，发起时也不再解析或落库。
 * 以前这里有个 {@code companyId}，还兼作 fail-closed 检查（为空即拒绝发起），
 * 那层检查随公司概念一起移除了。
 */
public record StartCmd(String formCode, Object data,
                       Map<String, List<Long>> selfSelected) {}
