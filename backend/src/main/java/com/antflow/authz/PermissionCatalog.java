package com.antflow.authz;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 能力点目录：代码是唯一权威，启动时同步进 t_permission。
 *
 * <p>每个能力点声明：域（由 code 前缀推导）、风险级别、是否超管专属、
 * 是否可配置数据范围（scopeable）以及默认范围（defaultScope）。
 * 角色授权只存"覆盖值"，未覆盖时取这里的默认值。
 */
public final class PermissionCatalog {

    public enum Risk { NORMAL, HIGH, CRITICAL }

    /** scopeable=false 时 defaultScope 必须为 null。 */
    public record Entry(String code, String name, Risk risk, boolean adminOnly,
                        DataScope defaultScope) {
        public boolean scopeable() {
            return defaultScope != null;
        }

        public String domain() {
            return code.substring(0, code.indexOf(':'));
        }
    }

    private static final Pattern CODE_PATTERN =
        Pattern.compile("[a-z][a-z0-9_]*:[a-z][a-z0-9_]*:[a-z][a-z0-9_]*");

    private static final List<Entry> ENTRIES = List.of(
        entry(PermissionCodes.CONSOLE_ACCESS, "进入管理端", Risk.HIGH),

        entry(PermissionCodes.ORG_COMPANY_READ, "查看企业信息", Risk.NORMAL),
        entry(PermissionCodes.ORG_DEPARTMENT_READ, "查看部门", Risk.NORMAL,
            DataScope.DEPARTMENT_AND_DESCENDANTS),
        entry(PermissionCodes.ORG_DEPARTMENT_MANAGE, "管理部门", Risk.HIGH,
            DataScope.DEPARTMENT),
        entry(PermissionCodes.ORG_USER_READ, "查看用户", Risk.NORMAL,
            DataScope.DEPARTMENT_AND_DESCENDANTS),
        entry(PermissionCodes.ORG_USER_MANAGE, "管理用户", Risk.HIGH, DataScope.DEPARTMENT),
        entry(PermissionCodes.ORG_USER_CREDENTIALS_MANAGE, "重置密码与登录控制", Risk.CRITICAL,
            true),

        entry(PermissionCodes.FORM_DEFINITION_READ, "查看表单", Risk.NORMAL),
        entry(PermissionCodes.FORM_DEFINITION_MANAGE, "创建与设计表单", Risk.HIGH),
        entry(PermissionCodes.FORM_DEFINITION_PUBLISH, "发布与停用表单", Risk.HIGH),
        entry(PermissionCodes.FORM_DEFINITION_DELETE, "删除表单", Risk.CRITICAL),
        entry(PermissionCodes.FORM_AUTHORIZATION_MANAGE, "管理表单使用范围与维护人员", Risk.HIGH),
        entry(PermissionCodes.FORM_OPTION_SOURCE_MANAGE, "管理共享选项数据源", Risk.HIGH, true),
        entry(PermissionCodes.FORM_RUNTIME_READ, "使用已发布表单", Risk.NORMAL),
        entry(PermissionCodes.FORM_DATA_READ, "查看表单数据", Risk.HIGH, DataScope.SELF),
        entry(PermissionCodes.FORM_DATA_EXPORT, "导出表单数据", Risk.HIGH, DataScope.SELF),

        entry(PermissionCodes.WORKFLOW_DEFINITION_READ, "查看流程配置", Risk.NORMAL),
        entry(PermissionCodes.WORKFLOW_DEFINITION_MANAGE, "设计流程配置", Risk.HIGH),
        entry(PermissionCodes.WORKFLOW_DEFINITION_PUBLISH, "发布流程配置", Risk.HIGH),
        entry(PermissionCodes.WORKFLOW_DEFINITION_DELETE, "删除流程配置", Risk.CRITICAL),
        entry(PermissionCodes.WORKFLOW_INSTANCE_START, "发起审批", Risk.NORMAL),
        entry(PermissionCodes.WORKFLOW_INSTANCE_READ, "查看审批记录", Risk.NORMAL, DataScope.SELF),
        entry(PermissionCodes.WORKFLOW_INSTANCE_WITHDRAW, "撤回审批", Risk.HIGH),
        entry(PermissionCodes.WORKFLOW_INSTANCE_OVERRIDE, "紧急干预审批", Risk.CRITICAL,
            DataScope.ALL),
        entry(PermissionCodes.WORKFLOW_MONITOR_READ, "查看流程监控", Risk.HIGH, DataScope.ALL),
        entry(PermissionCodes.WORKFLOW_TASK_READ, "查看本人任务", Risk.NORMAL),
        entry(PermissionCodes.WORKFLOW_TASK_APPROVE, "审批任务", Risk.HIGH),
        entry(PermissionCodes.WORKFLOW_TASK_REJECT, "驳回任务", Risk.HIGH),
        entry(PermissionCodes.WORKFLOW_TASK_TRANSFER, "转交任务", Risk.HIGH),
        entry(PermissionCodes.WORKFLOW_TASK_DELEGATE, "委托任务", Risk.HIGH),
        entry(PermissionCodes.WORKFLOW_TASK_ADD_ASSIGNEE, "加签任务", Risk.HIGH),
        entry(PermissionCodes.WORKFLOW_TASK_RECALL, "追回任务", Risk.HIGH),
        entry(PermissionCodes.WORKFLOW_AUTOMATION_RETRY, "重试流程自动化", Risk.HIGH,
            DataScope.ALL),

        entry(PermissionCodes.INTEGRATION_WECOM_MANAGE, "管理企业微信对接", Risk.HIGH),
        entry(PermissionCodes.INTEGRATION_IDENTITY_PROVIDER_MANAGE, "管理身份提供方", Risk.HIGH),
        entry(PermissionCodes.INTEGRATION_STORAGE_MANAGE, "管理对象存储", Risk.HIGH),

        entry(PermissionCodes.AUDIT_EVENT_READ, "查看操作日志", Risk.HIGH),
        entry(PermissionCodes.AUDIT_EVENT_EXPORT, "导出操作日志", Risk.HIGH),
        entry(PermissionCodes.AUDIT_ARCHIVE_DOWNLOAD, "下载日志归档", Risk.CRITICAL),

        entry(PermissionCodes.SECURITY_PERMISSION_READ, "查看权限目录", Risk.NORMAL),
        entry(PermissionCodes.SECURITY_ROLE_READ, "查看角色", Risk.NORMAL),
        entry(PermissionCodes.SECURITY_ROLE_MANAGE, "管理角色", Risk.HIGH, true),
        entry(PermissionCodes.SECURITY_USER_ROLE_READ, "查看用户权限", Risk.HIGH, true),
        entry(PermissionCodes.SECURITY_USER_ROLE_MANAGE, "管理用户权限", Risk.CRITICAL, true),
        entry(PermissionCodes.SECURITY_EFFECTIVE_READ, "预览有效权限", Risk.NORMAL),
        entry(PermissionCodes.SECURITY_MENU_MANAGE, "管理导航菜单", Risk.HIGH),

        entry(PermissionCodes.SYSTEM_COMPANY_MANAGE, "管理企业信息", Risk.HIGH),
        entry(PermissionCodes.SYSTEM_BACKUP_MANAGE, "管理系统备份", Risk.CRITICAL, true),

        entry(PermissionCodes.FILE_ATTACHMENT_READ, "读取附件", Risk.NORMAL),
        entry(PermissionCodes.FILE_ATTACHMENT_UPLOAD, "上传附件", Risk.NORMAL)
    );

    private static final Map<String, Entry> BY_CODE = index();
    private static final Set<String> ADMIN_ONLY = ENTRIES.stream()
        .filter(Entry::adminOnly).map(Entry::code)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Map<String, String> DOMAIN_LABELS = Map.of(
        "console", "控制台",
        "org", "组织架构",
        "form", "表单与数据",
        "workflow", "审批流转",
        "integration", "第三方对接",
        "audit", "日志审计",
        "security", "权限与安全",
        "system", "系统设置",
        "file", "文件"
    );

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Set<String> codes() {
        return BY_CODE.keySet();
    }

    public static Entry require(String code) {
        Entry entry = BY_CODE.get(code);
        if (entry == null) {
            throw new IllegalStateException("unknown permission code: " + code);
        }
        return entry;
    }

    public static boolean isKnown(String code) {
        return BY_CODE.containsKey(code);
    }

    public static boolean isAdminOnly(String code) {
        return ADMIN_ONLY.contains(code);
    }

    public static boolean isScopeable(String code) {
        return isKnown(code) && BY_CODE.get(code).scopeable();
    }

    /** 能力声明顺序派生的稳定排序号，供权限目录展示。 */
    public static int sortOrder(String code) {
        return (ENTRIES.indexOf(require(code)) + 1) * 10;
    }

    public static String domainLabel(String domain) {
        return DOMAIN_LABELS.getOrDefault(domain, domain);
    }

    public static boolean validCodeFormat(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }

    private static Entry entry(String code, String name, Risk risk) {
        return entry(code, name, risk, false, null);
    }

    private static Entry entry(String code, String name, Risk risk, DataScope defaultScope) {
        return entry(code, name, risk, false, defaultScope);
    }

    private static Entry entry(String code, String name, Risk risk, boolean adminOnly) {
        return entry(code, name, risk, adminOnly, null);
    }

    private static Entry entry(String code, String name, Risk risk, boolean adminOnly,
                               DataScope defaultScope) {
        if (!validCodeFormat(code)) {
            throw new IllegalStateException("permission code violates domain:resource:action: " + code);
        }
        if (!name.matches(".*[\\u4e00-\\u9fa5].*")) {
            // 目录面向中文管理端，名称必须可读
            throw new IllegalStateException("permission name must be Chinese: " + name);
        }
        return new Entry(code, name, risk, adminOnly, defaultScope);
    }

    private static Map<String, Entry> index() {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry entry : ENTRIES) {
            if (map.put(entry.code(), entry) != null) {
                throw new IllegalStateException("duplicate permission code: " + entry.code());
            }
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    private PermissionCatalog() {
    }
}
