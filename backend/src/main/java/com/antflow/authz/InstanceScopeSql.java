package com.antflow.authz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 把"调用者在某能力上的数据范围"翻译成作用在**流程实例**上的 SQL 谓词。
 *
 * <p>监控页（{@code workflow:monitor:read}）与报表（{@code form:data:read}）共用同一份口径：
 * 可视化范围一律落在**流程实例**上——发起人或发起部门，而不是任务的 assignee
 * （否则跨部门的代办会漏，或者反过来把别人的单子算进来）。
 *
 * <p>**fail-closed**：既不允许本人、也没有任何部门范围时返回恒假条件。取不到范围（没有请求主体）
 * 同样恒假——"取不到"绝不等于"范围是全部"。
 */
public final class InstanceScopeSql {

    /** 一段可直接拼进 WHERE 的谓词 + 它绑定的参数（参数顺序与占位符一致）。 */
    public record Scope(String sql, List<Object> args) { }

    /**
     * @param alias 主表在查询里的别名（本谓词里的列名都要带上它）
     */
    public static Scope forPermission(AuthorizationService authorization, String permission,
                                      String alias) {
        Optional<AuthorizationService.DataScopeFilter> current =
            authorization.currentDataScope(permission);
        // 空 = 没有请求主体（系统内部调用）。管理端接口正常走不到，但按上面的 fail-closed 处理。
        if (current.isEmpty()) {
            return new Scope("1 = 0", List.of());
        }
        if (current.get().admin() || current.get().unrestricted()) {
            return new Scope("1 = 1", List.of());
        }
        AuthorizationService.DataScopeFilter scope = current.get();
        List<String> parts = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (scope.selfAllowed()) {
            parts.add(alias + ".started_by = ?");
            args.add(scope.userId());
        }
        if (!scope.departmentIds().isEmpty()) {
            parts.add(alias + ".started_dept_id IN ("
                + String.join(",", Collections.nCopies(scope.departmentIds().size(), "?")) + ")");
            args.addAll(scope.departmentIds());
        }
        if (parts.isEmpty()) {
            return new Scope("1 = 0", List.of());
        }
        return new Scope("(" + String.join(" OR ", parts) + ")", List.copyOf(args));
    }

    /** 给"SQL 末尾还有 LIMIT ?"的查询拼参数：范围参数在前，limit 在最后。 */
    public static Object[] withLimit(List<Object> args, int limit) {
        Object[] all = new Object[args.size() + 1];
        for (int i = 0; i < args.size(); i++) {
            all[i] = args.get(i);
        }
        all[args.size()] = limit;
        return all;
    }

    private InstanceScopeSql() {
    }
}
