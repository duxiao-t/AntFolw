package com.antflow.automation;

import com.antflow.audit.AuditService;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「测试发送」：让流程设计者用当前（未保存的）触发器配置真实投递一次，并**回显 HTTP 状态 + 响应体**。
 *
 * <p>为什么需要：企业微信/钉钉等错误时仍返回 HTTP 200，失败原因只在响应体 errcode 里；
 * 没有这个入口，用户配错了也只能等真实运行、且系统里看不出所以然。
 *
 * <p>安全：仍走 {@link WebhookSecurityPolicy#validate}（allowed-hosts + 私网/回环/元数据黑名单 +
 * 生产强制 HTTPS），因此不会因为"能测试"就打开 SSRF 口子。
 */
@RestController
@RequestMapping("/api/automation/webhook")
public class WebhookTestController {

    private final WorkflowJobService jobs;
    private final WebhookClient webhookClient;
    private final ObjectMapper json;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public WebhookTestController(WorkflowJobService jobs, WebhookClient webhookClient,
                                 ObjectMapper json, AuthorizationService authorizationService,
                                 AuditService auditService) {
        this.jobs = jobs;
        this.webhookClient = webhookClient;
        this.json = json;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @PostMapping("/test")
    @PreAuthorize("@authz.console('" + PermissionCodes.WORKFLOW_DEFINITION_MANAGE + "')")
    public TestResult test(@RequestBody(required = false) TestRequest request) {
        authorizationService.requirePermission(PermissionCodes.WORKFLOW_DEFINITION_MANAGE);
        JsonNode props = request == null || request.props() == null
            ? json.createObjectNode() : request.props();
        JsonNode formData = request == null || request.formData() == null
            ? json.createObjectNode() : request.formData();
        long started = System.currentTimeMillis();
        try {
            JsonNode payload = jobs.buildTriggerPayload(props, formData);
            WebhookClient.DeliveryResult result = webhookClient.deliver(payload, UUID.randomUUID());
            JsonNode bodyJson = null;
            try {
                bodyJson = json.readTree(result.body());
            } catch (Exception ignored) {
                // 非 JSON 响应就只回显原文
            }
            String warning = result.successful()
                ? WebhookClient.applicationWarning(bodyJson) : null;
            auditService.success("automation.webhook.test", "WEBHOOK", null,
                AuditService.RiskLevel.NORMAL,
                Map.of("statusCode", result.statusCode()), Map.of());
            return new TestResult(result.statusCode(), result.successful(), warning,
                result.body(), System.currentTimeMillis() - started, null);
        } catch (Exception e) {
            // URL/主机被拦、密钥过短、连不上等都收敛成结构化结果，前端直接展示。
            return new TestResult(null, false, null, "",
                System.currentTimeMillis() - started, rootMessage(e));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
            ? current.getClass().getSimpleName() : message;
    }

    public record TestRequest(JsonNode props, JsonNode formData) { }

    public record TestResult(Integer statusCode, boolean successful, String warning,
                             String responseBody, long durationMs, String error) { }
}
