package com.antflow.authz;

public final class PermissionCodes {
    public static final String PAGE_WORKPLACE = "page.workplace";
    public static final String PAGE_ORG_CONTACTS = "page.org.contacts";
    public static final String PAGE_SECURITY_ROLES = "page.security.roles";
    public static final String PAGE_SECURITY_USER_PERMISSIONS = "page.security.user_permissions";
    public static final String PAGE_SECURITY_AUDIT_LOG = "page.security.audit_log";
    public static final String PAGE_APPROVAL_FORMS = "page.approval.forms";
    public static final String PAGE_APPROVAL_RECORDS = "page.approval.records";
    public static final String PAGE_REPORT_CENTER = "page.report.center";
    public static final String PAGE_REPORT_EXPORT = "page.report.export";
    public static final String PAGE_REPORT_DASHBOARD = "page.report.dashboard";
    public static final String PAGE_SETTINGS_COMPANY = "page.settings.company";
    public static final String PAGE_SETTINGS_S3 = "page.settings.s3";
    public static final String PAGE_SETTINGS_WECOM = "page.settings.wecom";
    public static final String PAGE_SETTINGS_IDENTITY_PROVIDERS = "page.settings.identity_providers";
    public static final String PAGE_SETTINGS_BILLING = "page.settings.billing";
    public static final String SECURITY_PERMISSION_READ = "security.permission.read";
    public static final String SECURITY_ROLE_READ = "security.role.read";
    public static final String SECURITY_ROLE_WRITE = "security.role.write";
    public static final String SECURITY_USER_ROLE_READ = "security.user_role.read";
    public static final String SECURITY_USER_ROLE_WRITE = "security.user_role.write";
    public static final String SECURITY_EFFECTIVE_READ = "security.effective.read";
    public static final String SECURITY_AUDIT_READ = "security.audit.read";
    public static final String SECURITY_AUDIT_EXPORT = "security.audit.export";
    public static final String SECURITY_AUDIT_ARCHIVE_DOWNLOAD = "security.audit.archive.download";
    public static final String ORG_COMPANY_MANAGE = "org.company.manage";
    public static final String ORG_DEPARTMENT_READ = "org.department.read";
    public static final String ORG_DEPARTMENT_WRITE = "org.department.write";
    public static final String ORG_USER_READ = "org.user.read";
    public static final String ORG_USER_WRITE = "org.user.write";
    public static final String FORM_DEFINITION_READ = "form.definition.read";
    public static final String FORM_DEFINITION_CREATE = "form.definition.create";
    public static final String FORM_DEFINITION_DESIGN = "form.definition.design";
    public static final String FORM_DEFINITION_PUBLISH = "form.definition.publish";
    public static final String FORM_DEFINITION_DELETE = "form.definition.delete";
    public static final String FORM_AUTHORIZATION_MANAGE = "form.authorization.manage";
    public static final String FORM_RUNTIME_READ = "form.runtime.read";
    public static final String FORM_DATA_READ = "form.data.read";
    public static final String FORM_DATA_EXPORT = "form.data.export";
    public static final String WORKFLOW_INSTANCE_START = "workflow.instance.start";
    public static final String WORKFLOW_INSTANCE_READ = "workflow.instance.read";
    public static final String WORKFLOW_INSTANCE_WITHDRAW = "workflow.instance.withdraw";
    public static final String WORKFLOW_INSTANCE_OVERRIDE = "workflow.instance.override";
    public static final String WORKFLOW_TASK_READ = "workflow.task.read";
    public static final String WORKFLOW_TASK_APPROVE = "workflow.task.approve";
    public static final String WORKFLOW_TASK_REJECT = "workflow.task.reject";
    public static final String WORKFLOW_TASK_TRANSFER = "workflow.task.transfer";
    public static final String WORKFLOW_TASK_DELEGATE = "workflow.task.delegate";
    public static final String WORKFLOW_TASK_ADD_ASSIGNEE = "workflow.task.add_assignee";
    public static final String WORKFLOW_TASK_RECALL = "workflow.task.recall";
    public static final String WORKFLOW_AUTOMATION_RETRY = "workflow.automation.retry";
    public static final String FILE_UPLOAD = "file.upload";
    public static final String FILE_READ = "file.read";

    private PermissionCodes() {
    }
}
