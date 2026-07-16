package com.antflow.task;

import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.CompleteCmd;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final ProcessEngine engine;
    private final TaskMapper taskMapper;

    @GetMapping
    public List<TaskEntity> myInbox(@RequestParam(defaultValue = "PENDING") String status) {
        var p = PrincipalHolder.current().orElseThrow();
        return taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("assignee_id", p.userId())
            .eq("status", status)
            .orderByDesc("created_at"));
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

    private static String asString(Object o) { return o == null ? null : o.toString(); }

    @PostMapping("/instances/{id}/withdraw")
    public void withdraw(@PathVariable Long id) {
        var p = PrincipalHolder.current().orElseThrow();
        engine.withdraw(id, p.userId());
    }
}
