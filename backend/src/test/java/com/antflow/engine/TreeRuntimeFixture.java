package com.antflow.engine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.test.util.ReflectionTestUtils;

/** These tests isolate tree traversal; persisted V2 state and concurrency are exercised in PostgreSQL. */
final class TreeRuntimeFixture {
    static ProcessEngine withRequiredV2(ProcessEngine engine) {
        WorkflowRuntimeV2 runtime = mock(WorkflowRuntimeV2.class);
        when(runtime.prepareStart(any(), any(), anyLong()))
            .thenReturn(new WorkflowRuntimeV2.StartState(1L, 1L));
        ReflectionTestUtils.setField(engine, "runtimeV2", runtime);
        return engine;
    }

    private TreeRuntimeFixture() { }
}
