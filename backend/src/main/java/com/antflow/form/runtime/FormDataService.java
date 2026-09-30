package com.antflow.form.runtime;

import com.antflow.common.FormalNumberService;
import com.antflow.common.BusinessNumberService;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import com.antflow.engine.BizException;
import com.antflow.process.DefinitionVersionRepository;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.org.UserMapper;
import com.antflow.mobile.workflow.MobileFileLinkService;
import com.antflow.mobile.workflow.MobileFileRef;
import com.antflow.mobile.workflow.MobileDraftService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FormDataService {
    private final FormDataMapper mapper;
    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper json;
    private final FormalNumberService formalNumberService;
    @Autowired(required = false)
    private BusinessNumberService businessNumbers;
    private final AuthorizationService authorizationService;
    private final UserMapper userMapper;
    private final com.antflow.org.DepartmentMapper departmentMapper;
    /** 台账里外链下拉的选项名要去选项行取（schema 里只有列映射与版本号）。 */
    private final com.antflow.form.options.OptionRuntimeService optionRuntime;
    private final MobileFileLinkService fileLinkService;
    private final MobileDraftService draftService;
    /** 可选注入：老的单测直接 new 本类，不给它传这个依赖。 */
    @Autowired(required = false)
    private DefinitionVersionRepository versions;

    /**
     * MVP demo — independent submission (DRAFT or SUBMITTED) outside the workflow engine.
     * Production: process instances must flow through {@code engine.start(...)}.
     */
    @Transactional
    public Long submit(String formCode, String status, Object data, Long userId) {
        return submit(formCode, status, data, userId, List.of()).dataId();
    }

    @Transactional
    public SubmitResult submit(String formCode, String status, Object data, Long userId,
                       List<MobileFileRef> files) {
        return submit(formCode, status, data, userId, files, null);
    }

    @Transactional
    public SubmitResult submit(String formCode, String status, Object data, Long userId,
                       List<MobileFileRef> files, Long draftId) {
        FormDefinition fd = formDefinitionService.getByCode(formCode);
        if (fd == null || !"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + formCode);
        }
        if (userId == null || authorizationService.currentUserId() != userId) {
            throw new AccessDeniedException("submission user does not match current principal");
        }
        authorizationService.requireFormUse(fd.getId());
        String normalizedStatus = status == null ? "SUBMITTED" : status;
        // 挂了已发布流程的表单只能走引擎发起。否则直接提交会造出 status=SUBMITTED、却没有
        // t_process_instance/审批任务的记录——桌面 /api/forms/data 与移动 /api/mobile/submissions
        // 都调这里，客户端只是"按 settings.workflowEnabled 自己选路"，服务端不兜底就能被绕过。
        if (!"DRAFT".equals(normalizedStatus) && versions != null
                && versions.hasPublishedProcess(fd.getId())) {
            throw new BizException("FORM_HAS_PROCESS",
                "该表单已启用审批流程，请从发起入口提交");
        }
        formDefinitionService.validateSubmission(fd.getSchema(), data);
        Object storedData = "DRAFT".equals(normalizedStatus)
            ? data
            : formDefinitionService.filterVisibleSubmission(fd.getSchema(), data);
        var fd2 = new FormData();
        fd2.setFormDefId(fd.getId());
        fd2.setFormDefVersion(fd.getVersion());
        if (!"DRAFT".equals(normalizedStatus)) {
            fd2.setBusinessNo(businessNumbers == null
                ? formalNumberService.businessNo() : businessNumbers.next(fd, storedData));
        }
        fd2.setData(writeJson(storedData));
        fd2.setStatus(normalizedStatus);
        fd2.setCreatedBy(userId);
        mapper.insert(fd2);
        fileLinkService.append(fd2.getId(), files, userId);
        if (draftId != null) draftService.deleteAfterSubmit(draftId, fd.getId(), userId);
        return new SubmitResult(fd2.getId(), fd2.getBusinessNo());
    }

    public record SubmitResult(Long dataId, String businessNo) { }

    public List<FormData> mySubmissions(Long userId, String formCode) {
        Long formDefId = null;
        if (formCode != null) {
            var fd = formDefinitionService.getByCode(formCode);
            // 指定了 code 却查不到（改名/下架/写错）时必须返回空：早先 formDefId 落成 null，
            // 而下传给 SQL 的 null 含义是"不过滤表单"，于是把该用户**所有**表单的提交都吐了出来。
            if (fd == null) return List.of();
            formDefId = fd.getId();
        }
        return mapper.selectMySubmissions(userId, formDefId);
    }

    public Page<FormData> adminPage(long page, long size, Long formDefId,
                                    String status, Long createdBy) {
        return adminPage(page, size, formDefId, status, createdBy, null);
    }

    /**
     * 台账分页。{@code submitterKeyword} 按**姓名或工号**筛提交人（列上显示的是姓名，
     * 筛选就不该再收数字 id）。
     *
     * <p>用子查询而不是"先查 id 集合再 IN"：一是避免无界参数列表，二是**天然保住"查不到人 = 零条"**——
     * 若改成先查集合、集合为空就省略这个条件，关键字无匹配会退化成"返回范围内的全部记录"。
     */
    public Page<FormData> adminPage(long page, long size, Long formDefId,
                                    String status, Long createdBy, String submitterKeyword) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);
        // 过滤条件与导出一份（关键字子查询、LIKE 转义这些只该有一处实现）。
        var q = exportFilter(formDefId, status, submitterKeyword, null, null);
        if (createdBy != null) q.eq("created_by", createdBy);
        return enrichAdminPage(mapper.selectPage(Page.of(safePage, safeSize), q));
    }

    public Page<FormData> authorizedPage(long page, long size, Long formDefId,
                                         String status, Long createdBy, String submitterKeyword,
                                         long userId, boolean admin) {
        // 非 admin 的能力数据范围由 DataPermissionPolicyHandler 在 SQL 层注入，
        // 这里统一走 SQL 分页，避免把整表读进内存再过滤。
        return adminPage(page, size, formDefId, status, createdBy, submitterKeyword);
    }

    /** LIKE 的通配符转义（配合 SQL 里的 `ESCAPE '\'`）。 */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 导出/预览用的行数上限（与审计导出一致）：再多就不该走同步下载了。 */
    public static final int EXPORT_LIMIT = 10_000;

    /**
     * 导出预览：先告诉用户"这次会导出多少行"。**参数必须与 {@link #exportRows} 完全一致**——
     * 少了时间范围，"预览 300 行"和实际下载的 30 行就对不上，`truncated` 还会误报。
     *
     * <p>刻意复用同一条过滤链（{@link #exportFilter} + {@code selectPage}）：它走的是
     * `FormDataMapper.selectList`，行级数据范围由 `DataPermissionPolicyHandler` 注入。
     * 自己写一条 count 查询的话那条规则不认（它是按语句 id 显式开启的），
     * "提示 30 行"就会变成实际的越权放大镜。
     */
    public long countForExport(Long formDefId, String status, String submitterKeyword,
                               java.time.OffsetDateTime from, java.time.OffsetDateTime to) {
        var q = exportFilter(formDefId, status, submitterKeyword, from, to);
        return mapper.selectPage(Page.of(1, 1), q).getTotal();
    }

    /** 导出的行（上限 {@link #EXPORT_LIMIT}）——过滤与范围同台账列表。 */
    public List<FormData> exportRows(Long formDefId, String status, String submitterKeyword,
                                     java.time.OffsetDateTime from, java.time.OffsetDateTime to) {
        var q = exportFilter(formDefId, status, submitterKeyword, from, to);
        // LIMIT 用常量拼（不是用户输入）；必须走 selectList 才吃得到行级范围注入。
        List<FormData> rows = mapper.selectList(q.last("LIMIT " + EXPORT_LIMIT));
        enrich(rows);
        return rows;
    }

    private QueryWrapper<FormData> exportFilter(Long formDefId, String status, String submitterKeyword,
                                                java.time.OffsetDateTime from,
                                                java.time.OffsetDateTime to) {
        var q = new QueryWrapper<FormData>();
        if (formDefId != null) q.eq("form_def_id", formDefId);
        if (status != null && !status.isBlank()) q.eq("status", status);
        if (submitterKeyword != null && !submitterKeyword.isBlank()) {
            q.apply("EXISTS (SELECT 1 FROM t_user submitter"
                + " WHERE submitter.id = t_form_data.created_by"
                + " AND (submitter.display_name LIKE {0} ESCAPE '\\'"
                + " OR submitter.employee_no LIKE {0} ESCAPE '\\'))",
                "%" + escapeLike(submitterKeyword.trim()) + "%");
        }
        if (from != null) q.ge("created_at", from);
        if (to != null) q.lt("created_at", to);
        return q.orderByDesc("created_at").orderByDesc("id");
    }

    public FormData getById(Long id) {
        FormData data = mapper.selectById(id);
        if (data == null) {
            throw new BizException("FORM_DATA_NOT_FOUND", "Form data not found: " + id);
        }
        return data;
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException("BAD_JSON", e.getMessage());
        }
    }

    private Page<FormData> enrichAdminPage(Page<FormData> page) {
        var records = page.getRecords();
        if (!records.isEmpty()) enrich(records);
        return page;
    }

    /** 批量回填提交人三列与字段值（列表与导出共用，导出的行数上限更大）。 */
    private void enrich(List<FormData> records) {
        if (records.isEmpty()) return;
        // created_by 在库里可为空（历史数据），一页可能全是无提交人的记录：空集合不能进
        // selectBatchIds（会拼出 IN ()），也不能进下面的部门批查。
        Set<Long> submitterIds = records.stream().map(FormData::getCreatedBy)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, com.antflow.org.User> users = submitterIds.isEmpty() ? Map.of()
            : userMapper.selectBatchIds(submitterIds).stream()
                .collect(Collectors.toMap(com.antflow.org.User::getId, Function.identity()));
        Set<Long> deptIds = users.values().stream().map(com.antflow.org.User::getDeptId)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> departmentNames = deptIds.isEmpty() ? Map.of()
            : departmentMapper.selectBatchIds(deptIds).stream()
                .collect(Collectors.toMap(com.antflow.org.Department::getId,
                    com.antflow.org.Department::getName));
        Map<Long, List<FormData.FieldValue>> values = fieldValues(records);
        records.forEach(record -> {
            // 显式挡 null 再查表：Map.of() 是 ImmutableCollections，`get(null)` 会抛 NPE。
            var user = record.getCreatedBy() == null ? null : users.get(record.getCreatedBy());
            if (user == null) {
                // 没有提交人（或人已被删）：三个字段都留空，由前端显示占位符。
                record.setCreatedByName(null);
                record.setCreatedByEmployeeNo(null);
                record.setCreatedByDeptName(null);
            } else {
                // 姓名优先显示名，为空回落账号——口径同 ProcessInstanceMapper 的 applicant_name。
                String displayName = user.getDisplayName();
                record.setCreatedByName(displayName == null || displayName.isBlank()
                    ? user.getUsername() : displayName);
                record.setCreatedByEmployeeNo(user.getEmployeeNo());
                record.setCreatedByDeptName(user.getDeptId() == null
                    ? null : departmentNames.get(user.getDeptId()));
            }
            record.setFieldValues(values.getOrDefault(record.getId(), List.of()));
        });
    }

    /**
     * 每条记录的字段值：**按记录自己的版本**取 schema，再交给 {@link FormValueDisplay} 出显示文本。
     *
     * <p>按解析出来的 schema 文本分组：同一版本的表单共用一份字典和外链标签，一页里混着多个版本的
     * 记录也各按各的来（这就是为什么缓存键是 schema 而不是 formDefId）。
     */
    private Map<Long, List<FormData.FieldValue>> fieldValues(List<FormData> records) {
        Map<Long, String> schemas = mapper.selectSchemas(
                records.stream().map(FormData::getId).toList()).stream()
            .collect(Collectors.toMap(FormDataMapper.SchemaRow::dataId,
                row -> java.util.Objects.toString(row.schema(), "")));
        Map<String, List<FormData>> groups = new java.util.LinkedHashMap<>();
        records.forEach(record -> groups
            .computeIfAbsent(schemas.getOrDefault(record.getId(), ""), key -> new java.util.ArrayList<>())
            .add(record));
        Map<Long, List<FormData.FieldValue>> values = new java.util.HashMap<>();
        groups.forEach((schema, group) -> {
            try {
                var datas = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                Map<Long, com.fasterxml.jackson.databind.JsonNode> byId = new java.util.HashMap<>();
                for (FormData record : group) {
                    var data = json.readTree(record.getData());
                    byId.put(record.getId(), data);
                    datas.add(data);
                }
                var display = new FormValueDisplay(json, json.readTree(schema), datas);
                for (var ref : display.pendingRefs()) {
                    display.bindLabels(ref.fieldId(), optionRuntime.labelsForValues(
                        ref.versionId(), ref.valueColumn(), ref.labelColumn(),
                        display.pendingValues(ref.fieldId())));
                }
                byId.forEach((id, data) -> values.put(id, display.values(data)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // schema/数据不是合法 JSON：这些记录退回"没有字段值"，整页不该因此 500。
            }
        });
        return values;
    }
}
