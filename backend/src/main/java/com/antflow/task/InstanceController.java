package com.antflow.task;

import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import com.antflow.engine.ProcessEngine;
import com.antflow.engine.dto.StartCmd;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/instances")
@RequiredArgsConstructor
public class InstanceController {
    private final ProcessEngine engine;
    private final ProcessInstanceMapper instanceMapper;
    private final TaskMapper taskMapper;
    private final TaskHistoryMapper historyMapper;

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody StartCmd cmd) {
        var p = PrincipalHolder.current().orElseThrow();
        return engine.start(cmd, p.userId());
    }

    @GetMapping
    public List<ProcessInstance> list(@RequestParam(required = false) String status) {
        var p = PrincipalHolder.current().orElseThrow();
        var q = new QueryWrapper<ProcessInstance>().eq("started_by", p.userId());
        if (status != null) q.eq("status", status);
        return instanceMapper.selectList(q);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        var pi = instanceMapper.selectById(id);
        if (pi == null) throw new BizException("NOT_FOUND", "instance not found");
        var tasks = taskMapper.selectList(new QueryWrapper<TaskEntity>().eq("proc_inst_id", id));
        var history = historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
            .eq("proc_inst_id", id).orderByAsc("created_at"));
        return Map.of("instance", pi, "tasks", tasks, "history", history);
    }

    @GetMapping("/{id}/history")
    public List<TaskHistoryEntity> history(@PathVariable Long id) {
        return historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
            .eq("proc_inst_id", id).orderByAsc("created_at"));
    }

    @PostMapping("/{id}/withdraw")
    public void withdraw(@PathVariable Long id) {
        var p = PrincipalHolder.current().orElseThrow();
        engine.withdraw(id, p.userId());
    }
}
