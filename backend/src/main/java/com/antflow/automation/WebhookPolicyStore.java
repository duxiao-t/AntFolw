package com.antflow.automation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Webhook 出站允许主机的**运行期来源**：环境变量基线 ∪ 后台追加（{@code t_webhook_setting}）。
 *
 * <p>为什么是"并集"而不是"覆盖"：允许列表是 SSRF 的安全边界。生产用 env 固守一份可信名单，
 * 管理员在后台只做**追加**，误把后台清空也不会退化成全拦/全放。私网/回环/元数据地址的黑名单
 * 始终在 {@link WebhookSecurityPolicy#isBlockedAddress} 里，不受本表影响。
 *
 * <p>不缓存：webhook 校验是"发布时一次 + 每次投递一次"，频次很低，直接读库最省心，
 * 也不存在多实例间缓存不一致的问题（与 {@code WecomAppConfigStore} 同款取舍）。
 */
@Component
public class WebhookPolicyStore {

    private final JdbcTemplate jdbc;

    public WebhookPolicyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 环境变量基线 + 后台追加，去重（大小写不敏感）并保持出现顺序。 */
    public List<String> effective(List<String> base) {
        List<String> merged = new ArrayList<>();
        if (base != null) merged.addAll(base);
        merged.addAll(dbHosts());
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String value : merged) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (trimmed.isEmpty()) continue;
            unique.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
        return new ArrayList<>(unique.values());
    }

    /** 后台配置的追加主机（原样返回，供管理页展示）。 */
    public List<String> dbHosts() {
        if (jdbc == null) return List.of();
        String raw;
        try {
            raw = jdbc.queryForObject(
                "SELECT allowed_hosts FROM t_webhook_setting WHERE id = 1", String.class);
        } catch (Exception e) {
            // 迁移尚未执行（极少见）或表不可用：退回空，出站仍受 env 基线约束。
            return List.of();
        }
        if (raw == null || raw.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String token : raw.split("[,\\n]")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /** 覆盖后台追加列表（已由调用方校验/规整）。 */
    public void save(List<String> hosts, Long updatedBy) {
        String joined = hosts == null ? "" : hosts.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
        jdbc.update("""
            UPDATE t_webhook_setting SET allowed_hosts = ?, updated_at = now(), updated_by = ?
            WHERE id = 1
            """, joined, updatedBy);
    }
}
