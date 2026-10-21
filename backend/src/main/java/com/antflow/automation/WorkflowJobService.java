package com.antflow.automation;

import com.antflow.engine.BizException;
import com.antflow.task.ProcessInstance;
import com.antflow.task.TaskHistoryEntity;
import com.antflow.task.TaskHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowJobService {
    private final WorkflowJobMapper mapper;
    private final TaskHistoryMapper historyMapper;
    private final ObjectMapper json;
    private final AutomationProperties properties;
    private final WebhookSecurityPolicy securityPolicy;

    @Transactional
    public JobCreation scheduleDelay(ProcessInstance instance, JsonNode node, long operatorId) {
        WorkflowJob existing = findNodeJob(instance, node, "DELAY");
        if (existing != null && reusable(existing)) return new JobCreation(existing, false);
        // 走到这里只有两种：本轮还没有作业，或既有作业是 CANCELLED（撤回取消过）。
        // 后者必须重建——复用已取消的作业会让节点停摆（见 V60 与 DelayHandler）。
        boolean rearmed = existing != null;
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime scheduledAt = DelaySchedule.calculate(
            node.path("props"), now, ZoneId.of(properties.getZoneId())
        );
        WorkflowJob job = newJob(instance, node, "DELAY", true, scheduledAt, node.path("props"));
        boolean immediate = !scheduledAt.isAfter(now);
        if (immediate) {
            job.setStatus("SUCCEEDED");
            job.setCompletedAt(now);
        }
        mapper.insert(job);
        insertHistory(instance.getId(), node.path("id").asText(), "DELAY_SCHEDULED",
            operatorId, "scheduledAt=" + scheduledAt + (rearmed ? "（撤回后重建）" : ""));
        if (immediate) {
            insertHistory(instance.getId(), node.path("id").asText(), "DELAY_COMPLETED",
                null, "scheduled time already passed");
        }
        return new JobCreation(job, true);
    }

    @Transactional
    public JobCreation queueTrigger(ProcessInstance instance, JsonNode node, JsonNode formData,
                                    long operatorId) {
        WorkflowJob existing = findNodeJob(instance, node, "TRIGGER");
        if (existing != null && reusable(existing)) return new JobCreation(existing, false);
        boolean rearmed = existing != null;
        JsonNode props = node.path("props");
        URI uri;
        try {
            uri = URI.create(props.path("url").asText());
        } catch (Exception e) {
            throw new BizException("BAD_WEBHOOK", "Webhook URL 无效");
        }
        securityPolicy.validate(uri);
        ObjectNode payload = json.createObjectNode();
        payload.put("method", props.path("method").asText("POST"));
        payload.put("url", uri.toString());
        payload.put("contentType", props.path("contentType").asText("application/json"));
        payload.put("secret", props.path("secret").asText());
        payload.set("headers", props.path("headers").isArray()
            ? props.path("headers").deepCopy() : json.createArrayNode());
        ObjectNode parameters = payload.putObject("parameters");
        for (JsonNode parameter : iterable(props.path("parameters"))) {
            String key = parameter.path("key").asText();
            if (key.isBlank()) continue;
            if ("FIELD".equals(parameter.path("source").asText())) {
                JsonNode value = formData.path(parameter.path("fieldId").asText());
                parameters.set(key, value.isMissingNode() ? json.nullNode() : value.deepCopy());
            } else {
                parameters.put(key, parameter.path("value").asText());
            }
        }
        boolean blocking = !"AFTER_SEND".equals(props.path("continueMode").asText("ON_SUCCESS"));
        WorkflowJob job = newJob(instance, node, "TRIGGER", blocking,
            OffsetDateTime.now(), payload);
        mapper.insert(job);
        insertHistory(instance.getId(), node.path("id").asText(), "TRIGGER_QUEUED",
            operatorId, rearmed ? "撤回后重建" : null);
        return new JobCreation(job, true);
    }

    @Transactional
    public WorkflowJob claimDue(String workerId) {
        return mapper.claimDue(workerId);
    }

    @Transactional
    public int recoverStale() {
        return mapper.recoverStale(OffsetDateTime.now().minus(properties.getStaleLockTimeout()));
    }

    /** 续租本 worker 正在投递的作业，避免长投递被 recoverStale 抢回重投。 */
    @Transactional
    public int renewOwnedLeases(String workerId) {
        return mapper.renewOwnedLeases(workerId);
    }

    @Transactional
    public void recordFailure(Long jobId, String error) {
        WorkflowJob job = mapper.selectForUpdate(jobId);
        if (job == null || !"RUNNING".equals(job.getStatus())) return;
        String sanitized = sanitizeError(error);
        int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
        if ("DELAY".equals(job.getJobType())) {
            job.setStatus("SCHEDULED");
            job.setScheduledAt(OffsetDateTime.now().plusSeconds(30));
        } else if (attempts >= job.getMaxAttempts()) {
            job.setStatus("FAILED");
            job.setCompletedAt(OffsetDateTime.now());
            insertHistory(job.getProcInstId(), job.getNodeId(), "TRIGGER_FAILED", null, sanitized);
        } else {
            long delaySeconds = Math.min(3600, 1L << Math.min(12, Math.max(0, attempts - 1)));
            job.setStatus("SCHEDULED");
            job.setScheduledAt(OffsetDateTime.now().plusSeconds(delaySeconds));
        }
        job.setLastError(sanitized);
        job.setLockedAt(null);
        job.setLockedBy(null);
        mapper.updateById(job);
    }

    @Transactional
    public void retryFailed(long instanceId, long jobId) {
        if (mapper.retryFailed(instanceId, jobId) == 0) {
            throw new BizException("BAD_JOB_STATE", "仅能重试当前实例中已失败的自动化作业");
        }
    }

    public List<WorkflowJobView> listViews(long instanceId) {
        return mapper.selectList(new QueryWrapper<WorkflowJob>()
                .eq("proc_inst_id", instanceId).orderByAsc("created_at"))
            .stream().map(WorkflowJobView::from).toList();
    }

    public JsonNode payload(WorkflowJob job) {
        try {
            return json.readTree(job.getPayload());
        } catch (JsonProcessingException e) {
            throw new BizException("BAD_JOB_PAYLOAD", "自动化作业载荷无效");
        }
    }

    /**
     * 取「本轮」该节点的作业（幂等键含轮次，见 V60）。
     *
     * <p>取到的行可能是终态：调用方必须用 {@link #reusable} 判断，别直接把 CANCELLED 当"已排队"。
     */
    private WorkflowJob findNodeJob(ProcessInstance instance, JsonNode node, String type) {
        return mapper.findNodeJob(instance.getId(), node.path("id").asText(), type, roundOf(instance));
    }

    /**
     * 可以原样复用的作业状态：
     * <ul>
     *   <li>SCHEDULED/RUNNING：等待中或投递中，交给调度器；</li>
     *   <li>SUCCEEDED：本轮已经跑过，handler 会直接放行；</li>
     *   <li>FAILED：等人工重试（{@code retryFailed}），不自动重建，避免掩盖持续失败；</li>
     *   <li>CANCELLED：**不可复用**——撤回把它取消了，复用会让节点 halt 且不建任务。</li>
     * </ul>
     */
    private static boolean reusable(WorkflowJob job) {
        return !"CANCELLED".equals(job.getStatus());
    }

    private static int roundOf(ProcessInstance instance) {
        return instance.getRoundNo() == null ? 1 : instance.getRoundNo();
    }

    private WorkflowJob newJob(ProcessInstance instance, JsonNode node, String type,
                               boolean blocking, OffsetDateTime scheduledAt, JsonNode payload) {
        WorkflowJob job = new WorkflowJob();
        job.setProcInstId(instance.getId());
        job.setNodeId(node.path("id").asText());
        job.setJobType(type);
        job.setRoundNo(roundOf(instance));
        job.setScheduledAt(scheduledAt);
        job.setStatus("SCHEDULED");
        job.setAttempts(0);
        job.setMaxAttempts(properties.getMaxAttempts());
        job.setDeliveryId(UUID.randomUUID());
        job.setPayload(writeJson(payload));
        job.setBlocking(blocking);
        return job;
    }

    private String writeJson(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException("BAD_JOB_PAYLOAD", e.getMessage());
        }
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        if (node instanceof ArrayNode array) return array;
        return List.of();
    }

    private void insertHistory(Long instanceId, String nodeId, String action,
                               Long operatorId, String comment) {
        TaskHistoryEntity history = new TaskHistoryEntity();
        history.setProcInstId(instanceId);
        history.setFromNodeId(nodeId);
        history.setToNodeId(nodeId);
        history.setAction(action);
        history.setOperatorId(operatorId);
        history.setComment(comment);
        historyMapper.insert(history);
    }

    private static String sanitizeError(String error) {
        if (error == null || error.isBlank()) return "Webhook delivery failed";
        String sanitized = error.replaceAll("(?i)(secret|authorization|token)=[^\\s,]+", "$1=***");
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000);
    }

    public record JobCreation(WorkflowJob job, boolean created) {}
}
