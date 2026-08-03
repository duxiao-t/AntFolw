package com.antflow.task;

import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.CompleteCmd;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final ProcessEngine engine;
    private final TaskOperationService ops;

    @GetMapping
    public List<TaskEntity> myInbox(@RequestParam(defaultValue = "PENDING") String status) {
        var p = PrincipalHolder.current().orElseThrow();
        return ops.listMyInbox(p.userId(), status);
    }

    @PostMapping("/{id}/approve")
    public void approve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        var p = PrincipalHolder.current().orElseThrow();
        engine.approve(new CompleteCmd(id, "APPROVE",
            body == null ? null : asString(body.get("comment")),
            null), p.userId());
    }

    @PostMapping("/{id}/reject")
    public void reject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        var p = PrincipalHolder.current().orElseThrow();
        engine.reject(new CompleteCmd(id, "REJECT",
            asString(body.get("comment")),
            asString(body.get("rejectToNodeId"))),
            p.userId());
    }

    @PostMapping("/instances/{id}/withdraw")
    public void withdraw(@PathVariable Long id) {
        var p = PrincipalHolder.current().orElseThrow();
        engine.withdraw(id, p.userId());
    }

    /** 转交：把任务给另一个人。原任务 SKIPPED；新任务 PENDING。 */
    @PostMapping("/{id}/transfer")
    public Map<String, Object> transfer(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        long targetUserId = Long.parseLong(asString(body.get("targetUserId")));
        String comment = asString(body.get("comment"));
        long newTaskId = ops.transfer(id, targetUserId, comment);
        return Map.of("newTaskId", newTaskId);
    }

    /** 委托：把任务镜像给另一个人。原任务不动。 */
    @PostMapping("/{id}/delegate")
    public Map<String, Object> delegate(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        long targetUserId = Long.parseLong(asString(body.get("targetUserId")));
        String comment = asString(body.get("comment"));
        long newTaskId = ops.delegate(id, targetUserId, comment);
        return Map.of("newTaskId", newTaskId);
    }

    /** 加签：在原任务基础上加一个 PENDING 子任务，与原任务一起 OR/AND 判定。 */
    @PostMapping("/{id}/add-assignee")
    public Map<String, Object> addAssignee(@PathVariable Long id,
                                            @RequestBody Map<String, Object> body) {
        long targetUserId = Long.parseLong(asString(body.get("targetUserId")));
        String comment = asString(body.get("comment"));
        long newTaskId = ops.addAssignee(id, targetUserId, comment);
        return Map.of("newTaskId", newTaskId);
    }

    /** 撤回子任务。TRANSFER 类型会恢复父任务；DELEGATE/ADD_ASSIGNEE 仅关闭子任务。 */
    @PostMapping("/{id}/recall-child")
    public void recallChild(@PathVariable Long id,
                            @RequestBody(required = false) Map<String, Object> body) {
        String comment = asString(body == null ? null : body.get("comment"));
        ops.recallChild(id, comment);
    }

    /** 列出某父任务的所有子任务（用于详情页展开转交/加签链路）。 */
    @GetMapping("/{id}/children")
    public List<TaskEntity> children(@PathVariable Long id) {
        return ops.listChildren(id);
    }

    private static String asString(Object o) { return o == null ? null : o.toString(); }
}