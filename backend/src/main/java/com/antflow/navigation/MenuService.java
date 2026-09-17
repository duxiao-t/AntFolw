package com.antflow.navigation;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 导航只做「编排」：菜单声明 pageKey 与所需能力（数组，全部满足才可见），
 * 不存组件路径与路由——页面是否存在由前端注册表裁决。
 * 保存后全量失效权限快照，用户无需重登即可看到新菜单。
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private static final int MAX_DEPTH = 3;
    private static final int MAX_NODES = 200;

    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorizationService;
    private final ObjectMapper objectMapper;
    private final PageCapabilityRegistry pageCapabilities;

    public List<NavNode> navigation() {
        PrincipalHolder.Principal principal = PrincipalHolder.current().orElseThrow();
        boolean admin = principal.isAdmin();
        Set<String> held = principal.permissions();
        return prune(loadTree(), admin, held);
    }

    public MenuDocument menu() {
        authorizationService.requirePermission(PermissionCodes.SECURITY_MENU_MANAGE);
        return new MenuDocument(currentVersion(), loadTree());
    }

    @Transactional
    public MenuDocument replace(MenuDocument request) {
        authorizationService.requirePermission(PermissionCodes.SECURITY_MENU_MANAGE);
        if (request == null || request.nodes() == null || request.nodes().isEmpty()) {
            throw new BizException("MENU_REQUIRED", "菜单树不能为空");
        }
        List<MenuNode> flat = new ArrayList<>();
        Set<String> pageKeys = new LinkedHashSet<>();
        flatten(request.nodes(), 1, flat, pageKeys, null, new int[] {0});
        if (pageKeys.isEmpty()) {
            throw new BizException("MENU_PAGE_REQUIRED", "菜单至少包含一个页面");
        }
        int updated = jdbcTemplate.update("""
            UPDATE t_menu_revision SET version = version + 1, updated_at = now()
            WHERE id = 1 AND version = ?
            """, request.version());
        if (updated != 1) {
            throw new BizException("MENU_VERSION_CONFLICT", "菜单已被其他人修改，请刷新后重试");
        }
        jdbcTemplate.update("DELETE FROM t_menu");
        Map<String, Long> inserted = new LinkedHashMap<>();
        int version = request.version() + 1;
        for (MenuNode node : flat) {
            Long parentId = node.parentKey() == null ? null : inserted.get(node.parentKey());
            Long id = jdbcTemplate.queryForObject("""
                INSERT INTO t_menu(parent_id, type, page_key, name_override, icon_override,
                                   required_permissions, sort_order, visible, version)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING id
                """, Long.class, parentId, node.type(), node.pageKey(), blankToNull(node.name()),
                blankToNull(node.icon()), toJson(node.requiredPermissions()), node.sortOrder(),
                node.visible() == null || node.visible(), version);
            inserted.put(node.nodeKey(), id);
        }
        bumpAllUsers();
        return new MenuDocument(version, loadTree());
    }

    /** 展开并校验：能力码必须存在、pageKey 必须属于前端注册表、目录节点不能带 pageKey。 */
    private void flatten(List<MenuNode> nodes, int depth, List<MenuNode> flat,
                         Set<String> pageKeys, String parentKey,
                         int[] directoryCounter) {
        if (depth > MAX_DEPTH) {
            throw new BizException("MENU_TOO_DEEP", "菜单最多 " + MAX_DEPTH + " 层");
        }
        for (MenuNode node : nodes) {
            if (flat.size() >= MAX_NODES) {
                throw new BizException("MENU_TOO_LARGE", "菜单节点过多");
            }
            boolean directory = "DIR".equals(node.type());
            if (!directory && !"PAGE".equals(node.type())) {
                throw new BizException("MENU_TYPE_INVALID", "菜单类型只能是 DIR 或 PAGE");
            }
            if (directory) {
                if (node.pageKey() != null && !node.pageKey().isBlank()) {
                    throw new BizException("MENU_PAGE_KEY_NOT_ALLOWED", "目录节点不能指定 pageKey");
                }
            } else {
                if (node.pageKey() == null || node.pageKey().isBlank()) {
                    throw new BizException("MENU_PAGE_KEY_REQUIRED", "页面菜单必须指定 pageKey");
                }
                if (!pageKeys.add(node.pageKey())) {
                    throw new BizException("MENU_PAGE_DUPLICATED", "同一页面不能出现在多处");
                }
            }
            List<String> requiredPermissions = directory
                ? List.of() : pageCapabilities.require(node.pageKey());
            String nodeKey = node.pageKey() != null && !node.pageKey().isBlank()
                ? node.pageKey() : "dir-" + (++directoryCounter[0]);
            flat.add(new MenuNode(nodeKey, parentKey, node.type(), node.pageKey(), node.name(),
                node.icon(), requiredPermissions, node.sortOrder(), node.visible(), null));
            if (node.children() != null && !node.children().isEmpty()) {
                flatten(node.children(), depth + 1, flat, pageKeys, nodeKey,
                    directoryCounter);
            }
        }
    }

    private List<MenuNode> loadTree() {
        List<MenuRow> rows = jdbcTemplate.query("""
            SELECT id, parent_id, type, page_key, name_override, icon_override,
                   required_permissions, sort_order, visible
            FROM t_menu ORDER BY sort_order, id
            """, (rs, index) -> new MenuRow(rs.getLong("id"),
            rs.getObject("parent_id", Long.class), rs.getString("type"), rs.getString("page_key"),
            rs.getString("name_override"), rs.getString("icon_override"),
            parsePermissions(rs.getString("required_permissions")), rs.getInt("sort_order"),
            rs.getBoolean("visible")));
        Map<Long, List<MenuRow>> byParent = new LinkedHashMap<>();
        for (MenuRow row : rows) {
            byParent.computeIfAbsent(row.parentId(), key -> new ArrayList<>()).add(row);
        }
        return build(byParent, null);
    }

    private List<MenuNode> build(Map<Long, List<MenuRow>> byParent, Long parentId) {
        return byParent.getOrDefault(parentId, List.of()).stream()
            .map(row -> new MenuNode(row.pageKey() != null ? row.pageKey() : "dir-" + row.id(),
                parentId == null ? null : String.valueOf(parentId), row.type(), row.pageKey(),
                row.nameOverride(), row.iconOverride(), row.pageKey() == null ? List.of()
                    : pageCapabilities.require(row.pageKey()), row.sortOrder(),
                row.visible(), build(byParent, row.id())))
            .toList();
    }

    /** 按能力过滤；目录在没有任何可见子项时一并隐藏。 */
    private List<NavNode> prune(List<MenuNode> nodes, boolean admin, Set<String> held) {
        List<NavNode> visible = new ArrayList<>();
        for (MenuNode node : nodes) {
            if (!Boolean.TRUE.equals(node.visible())) {
                continue;
            }
            List<NavNode> children = prune(node.children() == null ? List.of() : node.children(),
                admin, held);
            if (node.pageKey() == null) {
                if (!children.isEmpty()) {
                    visible.add(new NavNode(null, "DIR", node.name(), node.icon(),
                        node.requiredPermissions(), node.sortOrder(), children));
                }
                continue;
            }
            boolean allowed = admin || node.requiredPermissions() == null
                || node.requiredPermissions().stream().allMatch(held::contains);
            if (allowed) {
                visible.add(new NavNode(node.pageKey(), "PAGE", node.name(), node.icon(),
                    node.requiredPermissions(), node.sortOrder(), List.of()));
            }
        }
        return visible;
    }

    private int currentVersion() {
        Integer version = jdbcTemplate.queryForObject(
            "SELECT version FROM t_menu_revision WHERE id = 1", Integer.class);
        return version == null ? 0 : version;
    }

    private void bumpAllUsers() {
        jdbcTemplate.update("UPDATE t_user SET authz_version = authz_version + 1");
        authorizationService.evictAll();
    }

    private List<String> parsePermissions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("invalid required_permissions: " + json, error);
        }
    }

    private String toJson(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(permissions);
        } catch (Exception error) {
            throw new IllegalStateException("cannot serialize required_permissions", error);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record MenuRow(long id, Long parentId, String type, String pageKey, String nameOverride,
                           String iconOverride, List<String> requiredPermissions, int sortOrder,
                           boolean visible) { }

    /** 菜单节点：nodeKey 仅用于树内定位，不落库、不对外暴露主键。 */
    public record MenuNode(String nodeKey, String parentKey, String type, String pageKey,
                           String name, String icon, List<String> requiredPermissions,
                           int sortOrder, Boolean visible, List<MenuNode> children) { }

    public record MenuDocument(int version, List<MenuNode> nodes) { }

    /** 下发给前端渲染的导航节点。 */
    public record NavNode(String pageKey, String type, String name, String icon,
                          List<String> requiredPermissions, int sortOrder,
                          List<NavNode> children) { }
}
