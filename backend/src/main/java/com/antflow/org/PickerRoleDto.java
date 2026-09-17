package com.antflow.org;

/** 审批配置选择器只需要角色标识与展示名。 */
public record PickerRoleDto(Long id, String code, String name) { }
