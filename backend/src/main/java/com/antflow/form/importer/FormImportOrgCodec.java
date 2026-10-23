package com.antflow.form.importer;

import com.antflow.form.importer.FormImportValueCodec.DeptRef;
import com.antflow.form.importer.FormImportValueCodec.Prefetch;
import com.antflow.form.importer.FormImportValueCodec.UserRef;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 人员/部门/定位族：user_picker / dept_picker / location。
 *
 * <p>共同点是"文本只是线索，真值要靠字典反查"：人员与部门的字典在
 * {@link com.antflow.form.importer.FormImportValueCodec.Prefetch} 里一次性装好，
 * 本类只读内存索引、**不查库**。
 *
 * <p>重名不猜：同名的两个"张三"、两个"研发部"一律报错并列出候选（工号或全路径）。
 * 猜错的代价是历史数据挂到别人名下，后面所有审批与统计都跟着错。
 */
@Component
public class FormImportOrgCodec {

    Object users(JsonNode field, String label, String text, Prefetch prefetch) {
        boolean multiple = field.path("props").path("multiple").asBoolean(false);
        List<String> tokens = FormImportSupport.split(text);
        if (!multiple && tokens.size() > 1) {
            throw FormImportSupport.invalid(label, "只允许选择一个人员");
        }
        List<Long> ids = new ArrayList<>();
        for (String token : tokens) {
            ids.add(resolveUser(token, label, prefetch));
        }
        // 多位人员返回数组（FormFieldValueValidator 的 validUserPicker 认 List of Number）。
        return multiple ? ids : (ids.isEmpty() ? null : ids.get(0));
    }

    private Long resolveUser(String token, String label, Prefetch prefetch) {
        String value = token.trim();
        if (value.isEmpty()) throw FormImportSupport.invalid(label, "人员不能为空");
        if (value.startsWith("id:")) {
            long id = FormImportSupport.parseId(value.substring(3), label);
            UserRef user = prefetch.usersById.get(id);
            if (user == null) throw FormImportSupport.invalid(label, "人员 id " + id + " 不存在");
            return user.id();
        }
        UserRef byNo = prefetch.usersByEmployeeNo.get(value.toLowerCase(Locale.ROOT));
        if (byNo != null) return byNo.id();
        List<UserRef> byName = prefetch.usersByName.getOrDefault(value, List.of());
        if (byName.size() == 1) return byName.get(0).id();
        if (byName.size() > 1) {
            List<String> candidates = byName.stream()
                .map(user -> user.displayName() + "（工号 " + FormImportSupport.nullToDash(user.employeeNo()) + "）")
                .toList();
            throw FormImportSupport.invalid(label, "「" + value + "」有多个同名人员：" + String.join("、", candidates)
                + "；请改填工号或 id:编号");
        }
        throw FormImportSupport.invalid(label, "找不到人员「" + value + "」，请填工号或 id:编号");
    }

    Object departments(JsonNode field, String label, String text, Prefetch prefetch) {
        boolean multiple = field.path("props").path("multiple").asBoolean(false);
        List<String> tokens = FormImportSupport.split(text);
        if (!multiple && tokens.size() > 1) {
            throw FormImportSupport.invalid(label, "只允许选择一个部门");
        }
        List<Long> ids = new ArrayList<>();
        for (String token : tokens) ids.add(resolveDepartment(token, label, prefetch));
        return multiple ? ids : (ids.isEmpty() ? null : ids.get(0));
    }

    private Long resolveDepartment(String token, String label, Prefetch prefetch) {
        String value = token.trim();
        if (value.isEmpty()) throw FormImportSupport.invalid(label, "部门不能为空");
        if (value.startsWith("id:")) {
            long id = FormImportSupport.parseId(value.substring(3), label);
            if (!prefetch.deptsById.containsKey(id)) throw FormImportSupport.invalid(label, "部门 id " + id + " 不存在");
            return id;
        }
        List<DeptRef> byPath = prefetch.deptsByPath.getOrDefault(value, List.of());
        if (byPath.size() == 1) return byPath.get(0).id();
        List<DeptRef> byName = prefetch.deptsByName.getOrDefault(value, List.of());
        if (byName.size() == 1) return byName.get(0).id();
        if (byName.size() > 1) {
            List<String> candidates = byName.stream().map(dept ->
                deptPath(dept, prefetch.deptsById, new LinkedHashMap<>(), new LinkedHashSet<>())).toList();
            throw FormImportSupport.invalid(label, "部门「" + value + "」重名：" + String.join("、", candidates)
                + "；请改填全路径（总部/研发部）或 id:编号");
        }
        throw FormImportSupport.invalid(label, "找不到部门「" + value + "」，请填部门名称、全路径或 id:编号");
    }

    /**
     * 全路径（总部/研发部/平台组）。预取扫描与"部门重名"报候选两处共用，
     * 所以留在本族而不是门面——部门路径规则属于部门族的领域知识。
     */
    static String deptPath(DeptRef dept, Map<Long, DeptRef> byId,
                           Map<Long, String> memo, Set<Long> visiting) {
        if (memo.containsKey(dept.id())) return memo.get(dept.id());
        if (!visiting.add(dept.id())) return dept.name();
        String path = dept.name();
        DeptRef parent = dept.parentId() == null ? null : byId.get(dept.parentId());
        if (parent != null) path = deptPath(parent, byId, memo, visiting) + "/" + dept.name();
        visiting.remove(dept.id());
        memo.put(dept.id(), path);
        return path;
    }

    Map<String, Object> location(String label, String text) {
        String[] parts = text.split("[,，]", -1);
        if (parts.length != 2) throw FormImportSupport.invalid(label, "定位格式应为「纬度,经度」，如 30.12,120.65");
        double latitude;
        double longitude;
        try {
            latitude = Double.parseDouble(parts[0].trim());
            longitude = Double.parseDouble(parts[1].trim());
        } catch (NumberFormatException error) {
            throw FormImportSupport.invalid(label, "定位的经纬度不是数字");
        }
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
            || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw FormImportSupport.invalid(label, "经纬度超出合法范围");
        }
        // 顺序固定：显示层与测试都按这个顺序读，Map.of 的顺序不稳定。
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("latitude", latitude);
        value.put("longitude", longitude);
        value.put("coordinateSystem", "WGS84");
        return value;
    }
}
