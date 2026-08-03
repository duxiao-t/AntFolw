package com.antflow.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Non-CRUD helpers around ProcessInstance — split out so the engine
 * doesn't need casts or duplicate BaseMapper wiring.
 */
@Component
@RequiredArgsConstructor
public class TaskMapperExt {
    private final ProcessInstanceMapper processInstanceMapper;

    public Optional<ProcessInstance> selectInstanceById(long id) {
        return Optional.ofNullable(processInstanceMapper.selectById(id));
    }

    public void updateInstance(ProcessInstance pi) {
        processInstanceMapper.updateById(pi);
    }
}
