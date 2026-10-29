package com.antflow.authz;

/**
 * 原子能力点常量。命名规范：域:资源:动作。
 *
 * <p>只声明"能做什么"，不描述"显示在哪"。桌面端入口由 {@link #CONSOLE_ACCESS} 统一把关，
 * 菜单可见性由能力投影得出（见 t_menu.required_permissions）。
 *
 * <p>每个常量都必须登记在 {@link PermissionCatalog}，否则启动校验失败。
 */
public final class PermissionCodes {
    // 管理端入口
    public static final String CONSOLE_ACCESS = "console:entry:access";

    // 组织与人员
    public static final String ORG_DEPARTMENT_READ = "org:department:read";
    public static final String ORG_DEPARTMENT_MANAGE = "org:department:manage";
    public static final String ORG_USER_READ = "org:user:read";
    public static final String ORG_USER_MANAGE = "org:user:manage";
    public static final String ORG_USER_CREDENTIALS_MANAGE = "org:user_credentials:manage";

    // 表单定义与数据
    public static final String FORM_DEFINITION_READ = "form:definition:read";
    public static final String FORM_DEFINITION_MANAGE = "form:definition:manage";
    public static final String FORM_DEFINITION_PUBLISH = "form:definition:publish";
    public static final String FORM_DEFINITION_DELETE = "form:definition:delete";
    public static final String FORM_AUTHORIZATION_MANAGE = "form:authorization:manage";
    public static final String FORM_OPTION_SOURCE_MANAGE = "form:option_source:manage";
    public static final String FORM_RUNTIME_READ = "form:runtime:read";
    public static final String FORM_DATA_READ = "form:data:read";
    public static final String FORM_DATA_EXPORT = "form:data:export";
    /** 历史数据补录：直接写 SUBMITTED 台账行，不建流程实例，风险高于普通查看。 */
    public static final String FORM_DATA_IMPORT = "form:data:import";
    /**
     * 审批终态之后修正表单数据（不重走审批）。
     *
     * <p>风险高于普通读写：它会**改写已生效的业务结论**，所以必须同时满足
     * "持有本能力"与"是该表单的维护者"（全局管理员除外）。
     * 修正的字段还受服务端白名单约束，见 CorrectionFieldPolicy。
     */
    public static final String FORM_DATA_CORRECT = "form:data:correct";

    // 流程定义与运行
    public static final String WORKFLOW_DEFINITION_READ = "workflow:definition:read";
    public static final String WORKFLOW_DEFINITION_MANAGE = "workflow:definition:manage";
    public static final String WORKFLOW_DEFINITION_PUBLISH = "workflow:definition:publish";
    public static final String WORKFLOW_DEFINITION_DELETE = "workflow:definition:delete";
    public static final String WORKFLOW_INSTANCE_START = "workflow:instance:start";
    public static final String WORKFLOW_INSTANCE_READ = "workflow:instance:read";
    public static final String WORKFLOW_INSTANCE_WITHDRAW = "workflow:instance:withdraw";
    /** 发起人作废「待修改」申请（软终态，从本人视图隐藏）。 */
    public static final String WORKFLOW_INSTANCE_DISCARD = "workflow:instance:discard";
    public static final String WORKFLOW_INSTANCE_OVERRIDE = "workflow:instance:override";
    public static final String WORKFLOW_MONITOR_READ = "workflow:monitor:read";
    public static final String WORKFLOW_OUTBOX_RETRY = "workflow:outbox:retry";
    public static final String WORKFLOW_TASK_READ = "workflow:task:read";
    public static final String WORKFLOW_TASK_APPROVE = "workflow:task:approve";
    public static final String WORKFLOW_TASK_REJECT = "workflow:task:reject";
    public static final String WORKFLOW_TASK_TRANSFER = "workflow:task:transfer";
    public static final String WORKFLOW_TASK_DELEGATE = "workflow:task:delegate";
    public static final String WORKFLOW_TASK_ADD_ASSIGNEE = "workflow:task:add_assignee";
    public static final String WORKFLOW_TASK_RECALL = "workflow:task:recall";
    public static final String WORKFLOW_AUTOMATION_RETRY = "workflow:automation:retry";

    // 第三方对接
    public static final String INTEGRATION_WECOM_MANAGE = "integration:wecom:manage";
    /** 管理 Webhook 出站允许主机（SSRF 安全边界，仅管理员）。 */
    public static final String INTEGRATION_WEBHOOK_MANAGE = "integration:webhook:manage";
    public static final String INTEGRATION_IDENTITY_PROVIDER_MANAGE =
        "integration:identity_provider:manage";
    public static final String INTEGRATION_STORAGE_MANAGE = "integration:storage:manage";

    // 审计
    public static final String AUDIT_EVENT_READ = "audit:event:read";
    public static final String AUDIT_EVENT_EXPORT = "audit:event:export";
    public static final String AUDIT_ARCHIVE_DOWNLOAD = "audit:archive:download";

    // 权限与安全
    public static final String SECURITY_PERMISSION_READ = "security:permission:read";
    public static final String SECURITY_ROLE_READ = "security:role:read";
    public static final String SECURITY_ROLE_MANAGE = "security:role:manage";
    public static final String SECURITY_USER_ROLE_READ = "security:user_role:read";
    public static final String SECURITY_USER_ROLE_MANAGE = "security:user_role:manage";
    public static final String SECURITY_EFFECTIVE_READ = "security:effective:read";
    public static final String SECURITY_MENU_MANAGE = "security:menu:manage";

    // 系统设置
    public static final String SYSTEM_BACKUP_MANAGE = "system:backup:manage";

    // 文件
    public static final String FILE_ATTACHMENT_READ = "file:attachment:read";
    public static final String FILE_ATTACHMENT_UPLOAD = "file:attachment:upload";

    private PermissionCodes() {
    }
}
