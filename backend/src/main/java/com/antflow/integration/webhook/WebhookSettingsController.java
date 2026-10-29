package com.antflow.integration.webhook;

import com.antflow.audit.AuditService;
import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.automation.AutomationProperties;
import com.antflow.automation.WebhookPolicyStore;
import com.antflow.engine.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook 出站允许主机的运行期配置（管理员）。
 *
 * <p>允许列表 = 环境变量基线（只读，随部署固定）∪ 本页维护的追加项。页面只改"追加项"，
 * env 基线只做展示。私网/回环/云元数据地址的黑名单与生产强制 HTTPS 由
 * {@code WebhookSecurityPolicy} 兜底，不受本页影响。
 *
 * <p>权限自守：每个入口都校验 {@code integration:webhook:manage}（仅管理员），并且保存动作写审计，
 * 因为改动的是 SSRF 出站边界。
 */
@RestController
@RequestMapping("/api/settings/webhook")
public class WebhookSettingsController {

    private final WebhookPolicyStore store;
    private final AutomationProperties properties;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public WebhookSettingsController(WebhookPolicyStore store, AutomationProperties properties,
                                     AuthorizationService authorizationService,
                                     AuditService auditService) {
        this.store = store;
        this.properties = properties;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.INTEGRATION_WEBHOOK_MANAGE + "')")
    public WebhookSettingsView view() {
        authorizationService.requirePermission(PermissionCodes.INTEGRATION_WEBHOOK_MANAGE);
        List<String> envHosts = properties.getAllowedHosts() == null
            ? List.of() : new ArrayList<>(properties.getAllowedHosts());
        List<String> dbHosts = store.dbHosts();
        return new WebhookSettingsView(envHosts, dbHosts,
            store.effective(properties.getAllowedHosts()),
            properties.isHttpsOnly(), properties.isAllowPrivateAddresses());
    }

    @PutMapping
    @PreAuthorize("@authz.console('" + PermissionCodes.INTEGRATION_WEBHOOK_MANAGE + "')")
    public WebhookSettingsView save(@RequestBody WebhookSettingsRequest request) {
        authorizationService.requirePermission(PermissionCodes.INTEGRATION_WEBHOOK_MANAGE);
        List<String> hosts = normalize(request == null ? null : request.allowedHosts());
        Long operatorId = PrincipalHolder.current().map(PrincipalHolder.Principal::userId)
            .orElse(null);
        auditService.execute(() -> store.save(hosts, operatorId),
            () -> auditService.success("integration.webhook.settings.update", "WEBHOOK_SETTING", 1L,
                AuditService.RiskLevel.HIGH,
                Map.of("changedFields", List.of("allowedHosts")),
                Map.of("hosts", hosts)));
        return view();
    }

    /**
     * 规整并校验追加主机：逗号/换行分隔、去重、只允许 `host` 或 `*.suffix` 形式。
     * 明确拒绝协议、端口、路径——白名单只做主机名匹配，带 scheme/端口会静默不命中。
     */
    static List<String> normalize(List<String> raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        for (String entry : raw) {
            if (entry == null) continue;
            for (String token : entry.split("[,\\n]")) {
                String host = token.trim().toLowerCase(java.util.Locale.ROOT);
                if (host.isEmpty()) continue;
                if (!host.matches("(\\*\\.)?[a-z0-9]([a-z0-9.-]*[a-z0-9])?")) {
                    throw new BizException("BAD_WEBHOOK_HOST",
                        "Webhook 主机格式无效：" + token.trim()
                            + "（只填域名，如 qyapi.weixin.qq.com 或 *.example.com）");
                }
                if (!result.contains(host)) result.add(host);
            }
        }
        return result;
    }

    public record WebhookSettingsRequest(List<String> allowedHosts) { }

    public record WebhookSettingsView(List<String> envHosts, List<String> dbHosts,
                                      List<String> effective, boolean httpsOnly,
                                      boolean allowPrivateAddresses) { }
}
