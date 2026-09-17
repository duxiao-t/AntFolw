package com.antflow.mobile.workflow;

/** 角色选择器最小字段：配置审批人/抄送人时只需要 id 与展示名。 */
public record MobilePickerRoleDto(Long id, String code, String name) {
}
