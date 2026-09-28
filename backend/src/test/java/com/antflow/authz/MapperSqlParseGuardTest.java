package com.antflow.authz;

import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Method;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 解析守卫：数据权限拦截器会解析每条 SELECT/UPDATE/DELETE，因此 mapper 里使用的
 * PostgreSQL 专有语法必须能被 classpath 上的 JSqlParser（随 MyBatis-Plus 传入）解析。
 * 新增 mapper SQL 若用了不可解析的语法，这里会先失败。
 */
class MapperSqlParseGuardTest {
    private static final List<String> MAPPER_SQL_SHAPES = List.of(
        "SELECT * FROM t_form_definition form WHERE form.deleted = 0"
            + " AND form.name ILIKE CONCAT('%', ?, '%')",
        "SELECT * FROM t_department WHERE path <@ CAST(? AS ltree) ORDER BY path",
        "SELECT child.id FROM t_department child, t_department parent"
            + " WHERE child.id = ? AND parent.id = ? AND parent.path @> child.path",
        "SELECT pi.status AS status, COUNT(*) AS total,"
            + " COUNT(*) FILTER (WHERE pi.finished_at >= ? AND pi.finished_at < ?) AS finished_today"
            + " FROM t_process_instance pi GROUP BY pi.status",
        "SELECT * FROM t_process_instance WHERE id = ? FOR UPDATE",
        "SELECT * FROM t_form_data WHERE form_def_id = ? AND status IN (?, ?, ?)"
            + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
        "SELECT * FROM t_form_data WHERE data @> ?::jsonb",
        "WITH RECURSIVE tree AS (SELECT id FROM t_department) SELECT * FROM tree"
    );

    @Test
    void jsqlparserAcceptsEveryMapperSqlShapeWeRelyOn() {
        MAPPER_SQL_SHAPES.forEach(sql ->
            assertThatCode(() -> CCJSqlParserUtil.parse(sql))
                .as("mapper SQL must stay parseable by the data permission interceptor: %s", sql)
                .doesNotThrowAnyException());
    }

    @Test
    void generatedPermissionFragmentsAreParseable() {
        var scope = new AuthorizationService.DataScopeFilter(
            false, false, true, 42L, java.util.Set.of(3L, 5L));
        List.of(DataPermissionRules.FORM_DATA, DataPermissionRules.FORM_DEFINITION).forEach(rule ->
            assertThatCode(() -> CCJSqlParserUtil.parseCondExpression(
                DataPermissionPolicyHandler.buildSegment(
                    new net.sf.jsqlparser.schema.Table(rule.table()), rule, scope)))
                .as("generated condition must be parseable for %s", rule.table())
                .doesNotThrowAnyException());
    }

    /**
     * 拦截器会重新序列化它解析过的 SQL；PostgreSQL 的 `FOR UPDATE`/CTE/RETURNING 组合在
     * JSqlParser 往返后可能变成非法语句（`WITH ... FOR UPDATE SKIP LOCKED ORDER BY` 就是实例）。
     * 这类语句必须显式 `@InterceptorIgnore(dataPermission = "true")` 跳过解析。
     */
    @Test
    void riskyLockingStatementsMustOptOutOfDataPermissionParsing() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Class<?> mapper : mappers()) {
            for (Method method : mapper.getDeclaredMethods()) {
                String sql = statementSql(method);
                if (sql == null || !needsRoundTripProtection(sql)) {
                    continue;
                }
                InterceptorIgnore ignore = method.getAnnotation(InterceptorIgnore.class);
                if (ignore == null || !"true".equals(ignore.dataPermission())) {
                    offenders.add(mapper.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertThat(offenders)
            .as("mapper statements using FOR UPDATE / CTE / RETURNING must opt out of data "
                + "permission parsing to avoid JSqlParser round-trip rewriting")
            .isEmpty();
    }

    private static boolean needsRoundTripProtection(String sql) {
        String normalized = sql.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("FOR UPDATE")
            || normalized.contains("RETURNING")
            || normalized.stripLeading().startsWith("WITH ")
            || normalized.contains("\nWITH ");
    }

    private static String statementSql(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select != null && select.value().length > 0) {
            return String.join(" ", select.value());
        }
        Update update = method.getAnnotation(Update.class);
        if (update != null && update.value().length > 0) {
            return String.join(" ", update.value());
        }
        Delete delete = method.getAnnotation(Delete.class);
        if (delete != null && delete.value().length > 0) {
            return String.join(" ", delete.value());
        }
        return null;
    }

    private static List<Class<?>> mappers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider provider =
            new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AssignableTypeFilter(BaseMapper.class));
        List<Class<?>> found = new ArrayList<>();
        for (var candidate : provider.findCandidateComponents("com.antflow")) {
            found.add(Class.forName(candidate.getBeanClassName()));
        }
        return found;
    }
}
