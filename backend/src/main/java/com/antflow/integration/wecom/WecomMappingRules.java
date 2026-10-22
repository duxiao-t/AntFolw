package com.antflow.integration.wecom;

import com.antflow.integration.wecom.WecomClient.WecomDepartment;
import com.antflow.integration.wecom.WecomClient.WecomUser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 企微通讯录 → 本地组织的**映射规则**：怎么比、怎么选、字段怎么清洗。
 *
 * <p>为什么独立成类：这些方法全部是纯函数——不碰数据库、不参与事务、不依赖租约。
 * 留在 {@code WecomService}（1300+ 行）里时，"企微字段口径变了"和"同步任务怎么跑"这两类
 * 完全不同的需求都要动同一个文件。
 *
 * <p>**这里只做判定与转换，不做写入**。任何需要查库或改状态的逻辑都属于
 * {@link WecomService}（它持有事务与租约）。想在这里加 `JdbcTemplate` 就说明放错地方了。
 *
 * <p>两个类型（{@link LocalUserState}、{@link SyncUserException}）也定义在这里：它们是这套规则
 * 的输入/失败契约。{@code WecomService} 通过 import 引用它们，因此调用点无需改名。
 */
final class WecomMappingRules {

    private WecomMappingRules() { }

    /**
     * 外部成员与本地账号是否存在差异（决定要不要更新）。
     *
     * <p>注意这里的"差异"是**双向**的：本地被手工改过也算差异——同步是声明式对齐，
     * 不是"只补企微新增的字段"。
     */
    static boolean changed(WecomUser external, LocalUserState local) {
        return !Objects.equals(trim(local.displayName()), displayName(external))
            || !Objects.equals(local.username(), external.userId())
            || !Objects.equals(local.employeeNo(), external.userId())
            || !Objects.equals(local.status(), external.status() == 1 ? "ACTIVE" : "DISABLED")
            || !Objects.equals(blankToNull(local.position()), blankToNull(external.position()))
            || !sameWhenProvided(local.phone(), external.phone())
            || !sameWhenProvided(local.email(), external.email())
            || !Objects.equals(gender(local.gender()), gender(external.gender()))
            || !Objects.equals(local.wecomStatus(), external.status())
            || !Boolean.TRUE.equals(local.directoryPresent())
            || !Objects.equals(local.status(), effectiveUserStatus(
                external.status(), local.loginEnabledOverride()))
            || !sameDepartment(local, external);
    }

    /**
     * 企微状态 → 本地状态。
     *
     * <p>`loginEnabledOverride` 是**本地覆盖**：企微说停用(4) 时本地仍可强制启用；
     * 企微说启用(1) 时本地也可强制停用。这解决"企微还没开号，但本地要先放行"的场景。
     */
    static String effectiveUserStatus(int wecomStatus, Boolean loginEnabledOverride) {
        if (wecomStatus == 1) {
            return Boolean.FALSE.equals(loginEnabledOverride) ? "DISABLED" : "ACTIVE";
        }
        if (wecomStatus == 4 && Boolean.TRUE.equals(loginEnabledOverride)) return "ACTIVE";
        return "DISABLED";
    }

    /** 外部为空表示"这次没给"，不该覆盖本地已有值——这是同步里最容易写错的一处。 */
    private static boolean sameWhenProvided(String local, String external) {
        String normalized = blankToNull(external);
        return normalized == null || Objects.equals(blankToNull(local), normalized);
    }

    private static boolean sameDepartment(LocalUserState local, WecomUser external) {
        try {
            return local.wecomDepartmentId() == primaryDepartment(external);
        } catch (SyncUserException exception) {
            return false;
        }
    }

    /**
     * 部门经理链是否会成环。
     *
     * <p>数据库没有约束能表达"经理链无环"，只能在写入前用邻接表走一遍。之前漏掉这个检查时，
     * 一条 A→B→A 的经理关系会让同步反复互相覆盖，表现为"跑完还是不一致"。
     */
    static boolean createsCycle(long userId, Map<Long, Long> managers) {
        Set<Long> seen = new HashSet<>();
        Long current = userId;
        while (current != null) {
            if (!seen.add(current)) return true;
            current = managers.get(current);
        }
        return false;
    }

    /** 手机号/邮箱匹配的仲裁：命中多个、或两者指向不同人，一律拒绝自动绑定。 */
    static Long resolveMatch(List<Long> phoneMatches, List<Long> emailMatches) {
        if (phoneMatches.size() > 1 || emailMatches.size() > 1) {
            throw new SyncUserException("手机号或邮箱存在重复，无法自动绑定");
        }
        Set<Long> matches = new LinkedHashSet<>();
        matches.addAll(phoneMatches);
        matches.addAll(emailMatches);
        if (matches.size() > 1) {
            throw new SyncUserException("手机号和邮箱指向不同成员，无法自动绑定");
        }
        return matches.stream().findFirst().orElse(null);
    }

    /** 主部门：声明的主部门必须在部门列表里，否则退回第一个；都没有就报错（不能猜）。 */
    static long primaryDepartment(WecomUser user) {
        if (user.mainDepartment() > 0 && user.departmentIds().contains(user.mainDepartment())) {
            return user.mainDepartment();
        }
        if (!user.departmentIds().isEmpty()) return user.departmentIds().get(0);
        throw new SyncUserException("成员没有所属部门");
    }

    /** 按父子关系拓扑排序：先落父部门才能算出子部门的 ltree path。 */
    static List<WecomDepartment> orderDepartments(List<WecomDepartment> input) {
        Map<Long, WecomDepartment> byId = new LinkedHashMap<>();
        input.forEach(department -> {
            if (department.id() <= 0 || byId.put(department.id(), department) != null) {
                throw new WecomClient.WecomApiException("企业微信部门树包含重复部门");
            }
        });
        List<WecomDepartment> result = new ArrayList<>();
        Set<Long> visiting = new HashSet<>();
        Set<Long> visited = new HashSet<>();
        for (WecomDepartment department : input) {
            visitDepartment(department, byId, visiting, visited, result);
        }
        return result;
    }

    private static void visitDepartment(WecomDepartment department,
                                        Map<Long, WecomDepartment> byId, Set<Long> visiting,
                                        Set<Long> visited, List<WecomDepartment> result) {
        if (visited.contains(department.id())) return;
        if (!visiting.add(department.id())) {
            throw new WecomClient.WecomApiException("企业微信部门树存在循环");
        }
        if (department.parentId() != 0) {
            WecomDepartment parent = byId.get(department.parentId());
            if (parent == null) throw new WecomClient.WecomApiException("企业微信部门树缺少父部门");
            visitDepartment(parent, byId, visiting, visited, result);
        }
        visiting.remove(department.id());
        visited.add(department.id());
        result.add(department);
    }

    /** 姓名为空时退回 userId：本地 display_name 非空约束，不能留空。 */
    static String displayName(WecomUser user) {
        String name = trim(user.name());
        return name.isBlank() ? user.userId() : name.substring(0, Math.min(128, name.length()));
    }

    static String phone(WecomUser user) {
        return bounded(user.phone(), 32, "手机号");
    }

    static String email(WecomUser user) {
        return bounded(user.email(), 255, "邮箱");
    }

    static String position(WecomUser user) {
        return bounded(user.position(), 64, "职务");
    }

    static String bounded(String value, int limit, String label) {
        String normalized = blankToNull(value);
        if (normalized != null && normalized.length() > limit) {
            throw new SyncUserException(label + "超过 " + limit + " 个字符");
        }
        return normalized;
    }

    /** 企微用 "1"/"2" 表示性别，本地存中文——这条映射只在这里定义，别处不要重复判断。 */
    static String gender(String value) {
        return switch (trim(value)) {
            case "1" -> "男";
            case "2" -> "女";
            default -> null;
        };
    }

    static String blankToNull(String value) {
        String normalized = trim(value);
        return normalized.isBlank() ? null : normalized;
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 日志脱敏：外部账号只留首尾字符。
     *
     * <p>为什么放在规则类：它是"外部值 → 可展示值"的映射，和性别/姓名清洗同一类事情。
     * 注意**只用于日志**——写库路径必须用原始值，不要顺手拿它当校验。
     */
    static String mask(String value) {
        if (value == null || value.isBlank()) return "***";
        if (value.length() < 3) return value.charAt(0) + "***";
        return value.charAt(0) + "***" + value.charAt(value.length() - 1);
    }

    /** 本地账号的当前状态，作为 {@link #changed} 的输入。 */
    static record LocalUserState(long id, String username, String employeeNo, String phone,
                                 String email, String displayName, String position,
                                 String gender, String status, String wecomUserId,
                                 Integer wecomStatus, Boolean directoryPresent,
                                 Boolean loginEnabledOverride, long wecomDepartmentId,
                                 Long managerId) { }

    /** 单个成员同步失败：可继续处理其他人，最终汇总进 job 的错误列表。 */
    static class SyncUserException extends RuntimeException {
        SyncUserException(String message) {
            super(message);
        }
    }
}
