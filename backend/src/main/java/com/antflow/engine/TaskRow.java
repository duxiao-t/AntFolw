package com.antflow.engine;

/**
 * 批量取消/失效任务时的最小投影：只带 id 与接单人，避免把整个 {@code TaskEntity} 拉回来。
 *
 * <p>被决策、并行分支、任务参与、实例生命周期四簇共用，所以放在包级（与
 * {@code TaskLocker.LockedTask} 同样的理由：它不属于任何一簇的私有细节）。
 */
record TaskRow(long id, long assigneeId) { }
