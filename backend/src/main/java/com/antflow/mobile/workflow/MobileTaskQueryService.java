package com.antflow.mobile.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 移动端的列表查询：待办 / 已办、我发起的、我参与的流程。
 *
 * <p>分页口径是这一簇唯一会变的东西，所以集中在一处：
 * <ul>
 *   <li>页码下限 1、页大小夹在 1..50——前端传 0 或 500 时不报错，静默夹紧（移动端习惯）；</li>
 *   <li>多取一条（{@code size + 1}）来判断 {@code hasMore}，返回前截回 size：
 *       不用 {@code count(*)} 是因为这三条 SQL 的过滤条件都带公司快照，多数一次 count 比多取一行贵；</li>
 *   <li>关键字只做 trim，空串归一成 null，让 mapper 里的 {@code <if>} 整段跳过。</li>
 * </ul>
 *
 * <p>列表刻意只读快照 JSON 里的节点名（{@link MobileProjectionSupport#nodeName}），
 * 不再额外查表单/用户/部门：单测里 {@code verifyNoInteractions(instanceMapper, formDataMapper, ...)}
 * 守的就是这条"列表查询不放大成 N+1"。
 */
final class MobileTaskQueryService {
    private final MobileWorkflowMapper workflowMapper;
    private final MobileProjectionSupport projection;

    MobileTaskQueryService(MobileWorkflowMapper workflowMapper,
                           MobileProjectionSupport projection) {
        this.workflowMapper = workflowMapper;
        this.projection = projection;
    }

    MobilePageDto<MobileInstanceDto> listInstances(long userId, int page, int size,
                                                   String keyword, String status) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), 50);
        int fetchSize = normalizedSize + 1;
        int offset = (normalizedPage - 1) * normalizedSize;
        List<MobileInstanceDto> items = workflowMapper.selectInstancePage(userId,
                normalizedText(keyword), normalizedText(status), fetchSize, offset)
            .stream()
            .map(this::toInstanceDto)
            .toList();
        boolean hasMore = items.size() > normalizedSize;
        return new MobilePageDto<>(
            hasMore ? items.subList(0, normalizedSize) : items,
            hasMore
        );
    }

    MobilePageDto<MobileInitiatedDto> listInitiated(long userId, int page, int size,
                                                    String keyword, String status) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), 50);
        int fetchSize = normalizedSize + 1;
        int offset = (normalizedPage - 1) * normalizedSize;
        List<MobileInitiatedDto> items = workflowMapper.selectInitiatedPage(userId,
                normalizedText(keyword), normalizedText(status), fetchSize, offset)
            .stream().map(this::toInitiatedDto).toList();
        boolean hasMore = items.size() > normalizedSize;
        return new MobilePageDto<>(hasMore ? items.subList(0, normalizedSize) : items, hasMore);
    }

    MobilePageDto<MobileTaskDto> listTasks(String view, long userId, int page, int size,
                                           String keyword, String status) {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), 50);
        int fetchSize = normalizedSize + 1;
        int offset = (normalizedPage - 1) * normalizedSize;
        // 只认 "done"，其余一律按待办处理：前端历史版本曾传过 "DONE"/"todo"，
        // 收紧成白名单会让老客户端突然看到空列表。
        String normalizedView = "done".equalsIgnoreCase(view) ? "done" : "pending";
        List<MobileTaskDto> items = workflowMapper.selectTaskPage(userId, normalizedView,
                normalizedText(keyword), normalizedText(status), fetchSize, offset).stream()
            .map(this::toTaskDto)
            .toList();
        boolean hasMore = items.size() > normalizedSize;
        return new MobilePageDto<>(
            hasMore ? items.subList(0, normalizedSize) : items,
            hasMore
        );
    }

    private MobileInstanceDto toInstanceDto(MobileWorkflowMapper.InstanceRow row) {
        JsonNode snapshot = projection.readJsonObject(row.processSnapshot(), "BAD_FLOW_JSON");
        return new MobileInstanceDto(row.id(), row.status(), row.formName(), row.businessNo(),
            MobileProjectionSupport.nodeName(snapshot, row.currentNodeId()), row.startedAt(), row.finishedAt());
    }

    private MobileInitiatedDto toInitiatedDto(MobileWorkflowMapper.InitiatedRow row) {
        // DIRECT（无流程表单的直接提交）没有流程快照，节点名必须保持 null；
        // 这里若图省事去解析空字符串，会得到空对象再被 displayName 兜底成节点 id。
        String nodeName = "DIRECT".equals(row.kind()) ? null
            : MobileProjectionSupport.nodeName(
                projection.readJsonObject(row.processSnapshot(), "BAD_FLOW_JSON"),
                row.currentNodeId());
        return new MobileInitiatedDto(row.kind(), row.id(), row.status(), row.formName(),
            row.businessNo(), nodeName, row.startedAt(), row.finishedAt());
    }

    private MobileTaskDto toTaskDto(MobileWorkflowMapper.TaskRow row) {
        JsonNode snapshot = projection.readJsonObject(row.processSnapshot(), "BAD_FLOW_JSON");
        return new MobileTaskDto(row.id(), row.instanceId(), row.nodeId(), row.formCode(),
            row.formName(), row.businessNo(), row.applicantName(), row.applicantEmployeeNo(),
            row.applicantDepartment(), "REWORK".equals(row.taskType()) ? "待修改原单"
                : MobileProjectionSupport.nodeName(snapshot, row.nodeId()), row.taskType(), row.taskStatus(),
            row.instanceStatus(), row.createdAt(), row.readAt());
    }

    private static String normalizedText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
