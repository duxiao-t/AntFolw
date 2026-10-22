package com.antflow.integration.wecom;

import com.antflow.integration.wecom.WecomClient.WecomDepartment;
import com.antflow.integration.wecom.WecomSyncLease.LeaseLostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 企微部门树落库：建部门、维护 ltree 路径、同步部门负责人。
 *
 * <p>为什么从 {@link WecomDirectorySync} 再拆出来：成员同步与部门同步共享的只有
 * {@code jdbc}/{@code transactions}/{@code lease} 三个基础件，业务上没有交集——一个处理
 * {@code t_user}，一个处理 {@code t_department}。合在一起时类会重新长到 400 行以上，
 * 而门禁（H7）只允许下降，不允许"拆分产生新的超标类"。
 *
 * <p>两条约束：
 * <ol>
 *   <li>**入参必须已排好序**（父在前）：ltree 的 path 依赖父路径，本类不再自行排序。</li>
 *   <li>**每写一次都要 {@code lease.require()}**：部门树被两个实例同时改会出现 path 断裂。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class WecomDepartmentSync {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WecomSyncLease lease;

    Map<Long, Long> syncDepartments(long companyId, List<WecomDepartment> departments) {
        Map<Long, Long> mappings = new HashMap<>();
        for (WecomDepartment department : departments) {
            Long localId = transactions.execute(status -> {
                lease.require();
                Long parentId = department.parentId() == 0 ? null : mappings.get(department.parentId());
                if (department.parentId() != 0 && parentId == null) {
                    throw new WecomClient.WecomApiException("企业微信部门树缺少父部门");
                }
                Long existing = mappedDepartment(companyId, department.id());
                String parentPath = parentId == null ? null : jdbc.queryForObject(
                    "SELECT path::text FROM t_department WHERE id = ?", String.class, parentId);
                String path = (parentPath == null ? "" : parentPath + ".")
                    + "wc" + companyId + "d" + department.id();
                String name = department.name() == null ? "" : department.name().trim();
                if (name.isBlank()) throw new WecomClient.WecomApiException("企业微信部门名称为空");
                if (existing == null) {
                    Long created = jdbc.queryForObject("""
                        INSERT INTO t_department(company_id, parent_id, path, name, sort_order)
                        VALUES (?, ?, CAST(? AS ltree), ?, ?) RETURNING id
                        """, Long.class, companyId, parentId, path, name, department.order());
                    jdbc.update("""
                        INSERT INTO t_wecom_department_mapping(
                            company_id, wecom_department_id, department_id)
                        VALUES (?, ?, ?)
                        """, companyId, department.id(), created);
                    return created;
                }
                DepartmentState current = jdbc.query("""
                    SELECT company_id, path::text FROM t_department WHERE id = ?
                    """, rs -> rs.next() ? new DepartmentState(rs.getLong(1), rs.getString(2)) : null,
                    existing);
                if (current == null || current.companyId() != companyId) {
                    throw new WecomClient.WecomApiException("企业微信部门绑定无效");
                }
                if (!Objects.equals(current.path(), path)) {
                    jdbc.update("""
                        UPDATE t_department
                        SET path = CAST(? AS ltree) || subpath(path, nlevel(CAST(? AS ltree)))
                        WHERE path <@ CAST(? AS ltree) AND path <> CAST(? AS ltree)
                        """, path, current.path(), current.path(), current.path());
                }
                jdbc.update("""
                    UPDATE t_department
                    SET parent_id = ?, path = CAST(? AS ltree), name = ?, sort_order = ?
                    WHERE id = ?
                    """, parentId, path, name, department.order(), existing);
                return existing;
            });
            mappings.put(department.id(), localId);
        }
        return mappings;
    }

    List<String> syncDepartmentLeaders(long companyId, List<WecomDepartment> departments) {
        List<String> problems = new ArrayList<>();
        for (WecomDepartment department : departments) {
            try {
                List<String> leaders = new ArrayList<>(new LinkedHashSet<>(department.leaderUserIds()));
                Long leaderId = leaders.isEmpty() ? null : mappedActiveUser(companyId, leaders.get(0));
                transactions.executeWithoutResult(status -> {
                    lease.require();
                    Long departmentId = mappedDepartment(companyId, department.id());
                    if (departmentId == null) return;
                    jdbc.update("DELETE FROM t_department_leader WHERE department_id = ?",
                        departmentId);
                    if (leaderId != null) jdbc.update("""
                        INSERT INTO t_department_leader(department_id, user_id)
                        VALUES (?, ?) ON CONFLICT DO NOTHING
                        """, departmentId, leaderId);
                    jdbc.update("UPDATE t_department SET leader_id = ? WHERE id = ?",
                        leaderId, departmentId);
                });
                if (leaders.size() > 1) {
                    problems.add("部门 " + department.id() + " 配置了多位负责人，仅同步第一位");
                }
                if (!leaders.isEmpty() && leaderId == null) {
                    problems.add("部门 " + department.id() + " 的负责人未同步或已停用，已清空");
                }
            } catch (RuntimeException exception) {
                // 租约丢失不是"这个部门失败"，而是整轮都该停下——透传给调用方。
                if (exception instanceof LeaseLostException) throw exception;
                log.warn("WeCom department leader sync failed: company={}, department={}", companyId,
                    department.id(), exception);
                problems.add("部门 " + department.id() + " 的负责人同步失败");
            }
        }
        return problems;
    }

    private Long mappedDepartment(long companyId, long externalId) {
        return jdbc.query("""
            SELECT department_id FROM t_wecom_department_mapping
            WHERE company_id = ? AND wecom_department_id = ?
            """, rs -> rs.next() ? rs.getLong(1) : null, companyId, externalId);
    }

    private Long mappedActiveUser(long companyId, String externalId) {
        return jdbc.query("""
            SELECT user_row.id
            FROM t_wecom_user_mapping mapping
            JOIN t_user user_row ON user_row.id = mapping.user_id
            JOIN t_department department ON department.id = user_row.dept_id
            WHERE mapping.company_id = ? AND mapping.wecom_user_id = ?
              AND department.company_id = ? AND user_row.status = 'ACTIVE'
            """, rs -> rs.next() ? rs.getLong(1) : null, companyId, externalId, companyId);
    }

    /** 本地部门状态：只用于校验映射是否指向同一家公司。 */
    record DepartmentState(long companyId, String path) { }
}
