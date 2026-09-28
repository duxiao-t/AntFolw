package com.antflow.authz;

import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import org.springframework.stereotype.Component;

/**
 * 把"当前用户在某能力上的有效数据范围"翻译成 SQL 条件，交给 MyBatis-Plus 的
 * {@code DataPermissionInterceptor} 注入到受控查询上。
 *
 * <p>策略：admin → 不注入；非 admin 的 ALL 只省略能力范围条件，规则要求的资源条件仍保留；
 * 有主体但无该能力 → 注入 {@code 1=0}（宁可查不到，不可全量泄漏）；
 * 无主体（调度/同步等系统上下文）→ 不注入。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataPermissionPolicyHandler implements MultiDataPermissionHandler {

    private final AuthorizationService authorizationService;

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        log.debug("data permission visit: table={} statement={}", table.getName(), mappedStatementId);
        DataPermissionRules.Rule rule = DataPermissionRules.match(table.getName(), mappedStatementId);
        if (rule == null) {
            return null;
        }
        var scope = authorizationService.currentDataScope(rule.capability());
        if (scope.isEmpty() || scope.get().admin()) {
            return null;
        }
        if (!rule.dataScoped() && !authorizationService.hasPermission(rule.capability())) {
            return parse("1 = 0");
        }
        String segment = buildSegment(table, rule, scope.get());
        if (log.isDebugEnabled()) {
            log.debug("data permission inject: table={} statement={} condition={}",
                table.getName(), mappedStatementId, segment);
        }
        return parse(segment);
    }

    /** 包成包内可见的静态方法，便于单测直接断言生成的 SQL 片段。 */
    static String buildSegment(Table table, DataPermissionRules.Rule rule,
                               AuthorizationService.DataScopeFilter scope) {
        String alias = table.getAlias() == null ? table.getName() : table.getAlias().getName();
        List<String> conditions = new ArrayList<>();
        if (scope.selfAllowed() && rule.ownerColumn() != null) {
            conditions.add(alias + "." + rule.ownerColumn() + " = " + scope.userId());
        }
        if (!scope.departmentIds().isEmpty()) {
            String departments = scope.departmentIds().stream()
                .sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
            if (rule.departmentExpression() != null) {
                conditions.add(rule.departmentExpression()
                    .replace("{alias}", alias).replace("{departments}", departments));
            }
        }
        String scopePart = !rule.dataScoped() || scope.unrestricted() ? null : conditions.isEmpty()
            ? "1 = 0"
            : conditions.size() == 1 ? conditions.get(0)
                : "(" + String.join(" OR ", conditions) + ")";
        if (rule.requiredCondition() == null) {
            return scopePart == null ? "1 = 1" : scopePart;
        }
        String required = "(" + rule.requiredCondition()
            .replace("{alias}", alias).replace("{userId}", String.valueOf(scope.userId())) + ")";
        return scopePart == null ? required : required + " AND (" + scopePart + ")";
    }

    private static Expression parse(String segment) {
        try {
            return CCJSqlParserUtil.parseCondExpression(segment);
        } catch (Exception error) {
            throw new IllegalStateException("cannot build data permission condition: " + segment, error);
        }
    }
}
