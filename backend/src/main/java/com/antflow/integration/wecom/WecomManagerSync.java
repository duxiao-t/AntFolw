package com.antflow.integration.wecom;

import com.antflow.integration.wecom.WecomClient.WecomUser;
import com.antflow.integration.wecom.WecomClient.WecomUserRef;
import com.antflow.integration.wecom.WecomMappingRules.LocalUserState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 同步企微"直属上级"关系。
 *
 * <p>为什么独立：它是一块自洽的编排——并发拉每个成员的详情、判定上级、检测环、批量写
 * {@code t_user.manager_id}。留在 1100+ 行的 {@code WecomService} 里时，这段与"成员落库""部门树"
 * 没有任何共享逻辑，却要一起读。
 *
 * <p>三条不能改的约束：
 * <ol>
 *   <li>**必须走租约**：写 {@code manager_id} 前校验 {@link WecomSyncLease#require()}，
 *       否则两个实例会同时改同一批人。</li>
 *   <li>**并发度与间隔不能随意放宽**：{@link #MANAGER_POOL_SIZE} 与
 *       {@link #MANAGER_BATCH_INTERVAL_MS} 是按企微接口限流经验值定的，调大容易被限流封禁。</li>
 *   <li>**上级关系必须查环**：数据库没有约束表达"经理链无环"，环会让同步反复互相覆盖。</li>
 * </ol>
 *
 * <p>**错误聚合不在这里做**：本类只返回"发生了什么"，由调用方决定保留多少条
 * （{@code WecomService} 有 10 条上限与 160 字截断）。这样策略只有一份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class WecomManagerSync {

    /** 并发拉详情的线程数：按企微通讯录接口的 QPS 经验值定，调大易触发限流。 */
    private static final int MANAGER_POOL_SIZE = 12;
    /** 每批之间的间隔（毫秒）：给上游留出配额恢复时间，不要因为"同步慢"就调成 0。 */
    private static final long MANAGER_BATCH_INTERVAL_MS = 350;

    private final JdbcTemplate jdbc;
    private final WecomClient client;
    private final TransactionTemplate transactions;
    private final WecomSyncLease lease;

    /**
     * 同步直属上级。
     *
     * @param companyId 公司
     * @param session   已连接的企微会话
     * @param targets   本次涉及的成员（通常是全量外部成员）
     * @param mappedIds 外部 userId → 本地 userId（同步成员阶段已建立）
     * @param localUsers 本地成员当前状态，用于构建"现有上级关系"基线以查环
     * @return 本次产生的问题描述（可能为空）；**不做数量上限**，由调用方决定保留几条
     */
    List<String> sync(long companyId, WecomClient.Session session, List<WecomUser> targets,
                      Map<String, Long> mappedIds, Collection<LocalUserState> localUsers) {
        if (targets.isEmpty()) return List.of();
        List<String> problems = Collections.synchronizedList(new ArrayList<>());
        Map<String, WecomUser> usersByExternalId = targets.stream().collect(Collectors.toMap(
            WecomUser::userId, user -> user, (first, ignored) -> first, LinkedHashMap::new));
        ExecutorService pool = Executors.newFixedThreadPool(MANAGER_POOL_SIZE);
        List<ManagerUpdate> updates = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int offset = 0; offset < targets.size(); offset += MANAGER_POOL_SIZE) {
                List<WecomUser> chunk = targets.subList(offset,
                    Math.min(offset + MANAGER_POOL_SIZE, targets.size()));
                List<Future<?>> futures = new ArrayList<>();
                for (WecomUser user : chunk) {
                    futures.add(pool.submit(() -> {
                        try {
                            WecomUser full = client.user(session,
                                new WecomUserRef(user.userId(), user.departmentIds()));
                            ManagerUpdate update = managerUpdate(companyId, full, mappedIds,
                                usersByExternalId);
                            if (update != null) updates.add(update);
                        } catch (RuntimeException exception) {
                            log.warn("WeCom manager lookup failed: company={}, member={}", companyId,
                                WecomMappingRules.mask(user.userId()), exception);
                            problems.add("成员 " + WecomMappingRules.displayName(user) + "（工号 "
                                + user.userId() + "）直属上级读取失败，已保留原关系");
                        }
                    }));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (Exception ignored) {
                        // 单个成员的异常已经在上面记录，这里只等全部完成。
                    }
                }
                // 只在“后面还有批次”时节流；末尾无谓的睡眠只会无端占住调用线程（F7）。
                // ponytail: 仍是同步阻塞节流，彻底异步化需改 sync() 的返回契约，超出本次范围。
                if (offset + MANAGER_POOL_SIZE < targets.size()) {
                    try {
                        Thread.sleep(MANAGER_BATCH_INTERVAL_MS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return List.copyOf(problems);
                    }
                }
            }
        } finally {
            pool.shutdown();
        }
        if (updates.isEmpty()) return List.copyOf(problems);
        updates.sort(Comparator.comparingLong(ManagerUpdate::userId));
        // 基线用"库里现有的上级关系"，再叠加本批更新，才能查出"新关系导致的环"。
        Map<Long, Long> effectiveManagers = new HashMap<>();
        localUsers.forEach(user -> effectiveManagers.put(user.id(), user.managerId()));
        updates.forEach(update -> effectiveManagers.put(update.userId(), update.managerId()));
        List<Object[]> writes = new ArrayList<>();
        for (ManagerUpdate update : updates) {
            Long managerId = update.managerId();
            String issue = update.issue();
            if (managerId != null && WecomMappingRules.createsCycle(update.userId(), effectiveManagers)) {
                managerId = null;
                effectiveManagers.put(update.userId(), null);
                issue = "直属上级关系形成循环，已清空";
            }
            if (issue != null) {
                problems.add("成员 " + WecomMappingRules.displayName(update.user()) + "（工号 "
                    + update.user().userId() + "）" + issue);
            }
            writes.add(new Object[]{managerId, update.userId()});
        }
        transactions.executeWithoutResult(status -> {
            lease.require();
            jdbc.batchUpdate("UPDATE t_user SET manager_id = ? WHERE id = ?", writes);
        });
        return List.copyOf(problems);
    }

    /**
     * 判定一个成员的上级该写谁。**包内可见**：单测需要断言各分支的判定结果，走完整的
     * {@link #sync} 要 mock 并发与事务，成本远高于收益。
     *
     * @return null 表示这个成员不在本地映射里，整条跳过；否则返回一条更新（`managerId` 为 null
     *         表示要清空，`issue` 非空表示有需要汇报的问题）
     */
    ManagerUpdate managerUpdate(long companyId, WecomUser user, Map<String, Long> mappedIds,
                                Map<String, WecomUser> usersByExternalId) {
        Long userId = mappedIds.get(user.userId());
        if (userId == null) return null;
        List<String> leaders = user.directLeaders();
        if (leaders.isEmpty()) return new ManagerUpdate(userId, null, user, null);
        WecomUser manager = usersByExternalId.get(leaders.get(0));
        Long managerId = manager == null ? null : mappedIds.get(manager.userId());
        String issue = leaders.size() > 1 ? "配置多位直属上级，仅同步第一位；" : "";
        if (manager == null || manager.status() != 1 || managerId == null
            || !activeCompanyUser(companyId, managerId)) {
            return new ManagerUpdate(userId, null, user, issue + "直属上级未同步或已停用，已清空");
        }
        if (Objects.equals(userId, managerId)) {
            return new ManagerUpdate(userId, null, user, issue + "直属上级不能为本人，已清空");
        }
        return new ManagerUpdate(userId, managerId, user, issue.isEmpty() ? null : issue);
    }

    /** 上级必须同时是"本公司"且"在职"——跨公司或已停用的上级写进去会让组织树查不到人。 */
    private boolean activeCompanyUser(long companyId, long userId) {
        Boolean active = jdbc.query("""
            SELECT user_row.status = 'ACTIVE'
            FROM t_user user_row
            JOIN t_department department ON department.id = user_row.dept_id
            WHERE user_row.id = ? AND department.company_id = ?
            """, rs -> rs.next() ? rs.getBoolean(1) : null, userId, companyId);
        return Boolean.TRUE.equals(active);
    }

    /** 待写入的一条上级关系。`issue` 是给用户看的说明，null 表示无需汇报。包内可见以便单测断言。 */
    record ManagerUpdate(long userId, Long managerId, WecomUser user, String issue) { }
}
