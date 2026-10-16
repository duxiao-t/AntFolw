package com.antflow.engine.dto;

import java.util.List;
import java.util.Map;

/**
 * @param companyId **已解析好的**公司归属（P4a）。调用方必须先用 {@code CompanyAttribution} 解析：
 *                  普通用户由身份里的部门推导；无部门的全局管理员用请求里显式指定的那个。
 *                  **引擎不自己解析**——它分不清"用户显式选的"与"从草稿继承的"，
 *                  而草稿转正式必须沿用草稿行里的快照（否则调岗后会被改归属）。
 *                  引擎只保留一道 fail-closed 检查：为 null 直接拒绝，绝不静默写入无归属的记录。
 */
public record StartCmd(String formCode, Object data,
                       Map<String, List<Long>> selfSelected, Long companyId) {}
