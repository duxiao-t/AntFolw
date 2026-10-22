# ProcessEngine 拆分方案（2026-10-22）

原类：`backend/src/main/java/com/antflow/engine/ProcessEngine.java`，**1277 行 / 77 方法**，是当前最大类。

## 一、先厘清 V1 / V2 两条路径（拆之前必须知道）

### 分流机制

`rejectInternal` 里的判定：

```java
if (runtimeV2 != null && runtimeV2.active(pi) && t.getNodeInstanceId() != null) {
    … settleV2Decision(…)   // V2
    return;
}
… settleLegacyRejection(…)  // V1
```

`WorkflowRuntimeV2.active(pi)` 的定义是 `Objects.equals(instance.getEngineVersion(), 2)`。

### 谁写 engineVersion

```
ProcessEngine.java:140   pi.setEngineVersion(2);
```

**唯一写入点，硬编码 2**。也就是说：**所有新实例都是 V2**；`t_process_instance` 的唯一写入方是
`WorkflowRuntimeV2`。V1 是**只读兼容层**，只服务历史实例。

### 覆盖现状（重要修正）

原先以为"V1 没有测试"，实际相反：

| 路径 | 覆盖 | 说明 |
| --- | --- | --- |
| **V1**（`settleLegacyRejection`） | **4 个单测** | `ProcessEngineTreeTest` 的 reject 用例（747/814/877/943）。测试从未设置 `engineVersion`，默认 null → `active()` 为 false → **它们走的全是 V1** |
| **V2**（`settleV2Decision`） | **44 处集成测试引用** | `PostgresTransactionalIntegrityIntegrationTest`，需要 Testcontainers（CI 跑，本地无 Docker） |

结论：**不需要补特征测试**，两条路径都有覆盖；只是 V2 的保护在集成测试而非单测，拆完必须过 CI。

## 二、拆分计划（按"变化原因"切，共五刀）

| 刀 | 目标类 | 内容 | 估算 |
| --- | --- | --- | --- |
| 1 | `LegacyDecisionSettler` | `settleLegacyRejection` + `legacyNodeVotes` + `requiredApprovals` | ~100 行 |
| 2 | `V2DecisionSettler` | `settleV2Decision` + `advanceParallelBranch` + `firstRejectedTask` + `fixedRejectTarget` | ~130 行 |
| 3 | `ReworkService` | `resubmitRework` + `previousApproval` + `previousNodeAssignees` + `newReturnedTask` | ~80 行 |
| 4 | `WithdrawService` | `withdraw` + `canWithdraw` + `canWithdrawCurrentRound` | ~80 行 |
| 5 | `AutomationCompleter` | `completeAutomation` + `completeTaskTimeout` + `finishTimeoutJob` | ~110 行 |

预计 1277 → 约 780，仍超 400，需要第二批次（启动、审批推进、节点落地、字段编辑）。

**为什么不一次拆完**：每一刀都要过全量测试（含 1708 行引擎测试），一次改太多会让失败定位变难；
且第 1、2 刀是对称的（V1/V2 结算），拆完能立刻验证"两条路径互不干扰"。

## 三、依赖方向

```
ProcessEngine（编排：锁任务 → 判分流 → 调结算）
   ├─ LegacyDecisionSettler   只读 root/task/instance，写 task/history/formData
   ├─ V2DecisionSettler       读 WorkflowRuntimeV2.Decision，写 task/instance/history
   ├─ ReworkService / WithdrawService / AutomationCompleter
   └─ WorkflowRuntimeV2（既有）
```

**禁止**：结算类反向依赖 `ProcessEngine`。它们需要的上下文（root、instance、task）全部作为参数传入。

## 四、风险

1. **V1/V2 对称性**：两个结算器共用 `previousApproval` / `newReturnedTask` / `insertHistory` 等工具，
   第一刀时先让 V1 用「原类保留的工具方法」，第二刀再把工具下沉到共享处——否则第一刀就要动 V2 的调用点。
2. **事务边界**：`settleXxx` 目前在调用方的事务里执行，搬出后**不能加自己的 `@Transactional`**。
3. **锁顺序**：`lockPendingTask` 在分流前执行，搬出的是"分流之后"的逻辑，锁语义不变。

## 五、执行记录（2026-10-22，五刀全部落地）

行数/方法数按 `scripts/health-metrics.mjs` 的 H7 口径（4 空格缩进 + 可见性正则）。注意方案开头写的
"77 方法"其实是基线里**最大类**的方法数，ProcessEngine 实测是 48 个方法。

| 阶段 | ProcessEngine 行 / 方法 | 本刀产出 |
| --- | --- | --- |
| 拆前 | 1277 / 48 | — |
| 第 1 刀 | 1186 / 48 | `LegacyDecisionSettler`（150 / 3）、`EngineSupport`（191 / 11）、`FlowAdvancer`（44 / 4） |
| 第 2 刀 | 1082 / 43 | `V2DecisionSettler`（166 / 4） |
| 第 3 刀 | 1038 / 43 | `ReworkService`（112 / 1） |
| 第 4 刀 | 974 / 42 | `WithdrawService`（137 / 3） |
| 第 5 刀 | 879 / 41 | `AutomationCompleter`（166 / 3） |
| 收尾（删空壳 + 补注释） | **874 / 40** | — |

与原方案的三处偏差（都是有意的）：

1. **新增 `EngineSupport` + `FlowAdvancer` 两个类**。`previousApproval` / `newReturnedTask` /
   `insertHistory` / `readProcessTree` 这批工具被 V1、V2、返工、撤回、自动化五处共用，
   放回"某一刀"的类里必然造成别处反向依赖；下沉到 `EngineSupport`（非 bean，与 ProcessEngine
   共用同一批 mapper）后，"逐字一致"才有唯一的对照物。`FlowAdvancer` 是 ProcessEngine 实现的
   回调接口，用来把"结算类 → ProcessEngine"的反向依赖切掉（Spring 也不允许这条构造器环）。
2. **`advanceParallelBranch` 留在 ProcessEngine**，没有进 `V2DecisionSettler`。它有 3 个调用方
   （approve、V2 结算、自动化完成），且与 `resolveAndLand` 是同一套节点落地逻辑；搬进 V2 类会形成
   "ProcessEngine → V2Settler → FlowAdvancer(ProcessEngine)"的回边，比分刀本身更难维护。
3. **第 3 刀没有搬 `previousApproval` / `previousNodeAssignees` / `newReturnedTask`**：
   `resubmitRework` 实际不用它们，第 1 刀已随 V1 结算一并落到 `EngineSupport`。

其它记录：

- `ProcessEngine` 改用显式构造器（替换 Lombok `@RequiredArgsConstructor`）：协作者需要 `this` 作回调口，
  而且测试里 `new ProcessEngine(14 个参数)` 的 4 处调用点因此一行都不用改（`src/test` 零改动）。
- `runtimeV2` 仍由字段注入（H5 口径不变），协作者通过 `Supplier` 延迟读取——否则构造期拿到的是 null，
  单测里 `ReflectionTestUtils.setField` 替换运行时也会失效。
- 唯一的行为可见差异：`NotificationEvent` 的 `source` 从 ProcessEngine 变成了各协作者实例
  （全仓无读取方，类型/接收人/文案不变）；超时代批由 `approveInternal(..., true, action)` 改为
  `FlowAdvancer.forceApproveAsSystem(...)`，force 标志移进接口实现。

拆完仍有 874 行（>400），下一批次按原计划继续：启动（`start` + 表单归属/编号）、审批推进
（`approveInternal` + `rejectInternal` + `skipDelegatedParentIfNeeded`）、节点落地
（`resolveAndLand*` + `advanceParallelBranch` + `pickHandler` + `notifyAssigned`）、字段编辑
（`applyNodeFieldEdits` + `editableFieldIds`）。其中"节点落地"是一组，必须整体搬，不能只搬一半。

## 六、第二批次执行记录（2026-10-22，六刀 + 一次收尾）

按"变化原因"切，全部方法体逐字搬迁（只改可见性、协作者前缀、`runtimeV2` → 局部 `v2`）。
每刀后跑 `mvn -B -q compile` + `mvn -B test -Dtest='ProcessEngine*'`，**每刀 33 例全绿**。

| 阶段 | ProcessEngine 行 / 方法 | 本刀产出（行 / 方法） |
| --- | --- | --- |
| 拆前 | 874 / 40 | — |
| 第 1 刀 | 816 / 38 | `FieldEditApplier`（124 / 2）：`applyNodeFieldEdits` + `editableFieldIds` |
| 第 2 刀 | 645 / 34 | `NodeLander`（267 / 7）：`resolveAndLand*` + `advanceParallelBranch` + `pickHandler` + `notifyAssigned` + `hasActiveParallelAutomation` |
| 第 3 刀 | 579 / 34 | `ProcessStarter`（172 / 1）：`start` 全流程（表单 + 实例 + 首节点 + INSTANCE_STARTED） |
| 第 4 刀 | 545 / 32 | `TaskLocker`（102 / 3）：`lockPendingTask` + `updateTaskOrConflict` + `skipDelegatedParentIfNeeded`（+ `LockedTask`） |
| 第 5 刀 | 527 / 32 | `AdminActions`（86 / 3）：`recallApproval` + `adminReassign` + `adminTerminate` |
| 收尾（薄壳清理） | 473 / 21 | 删掉 11 个已无调用方的薄壳（7 个 `read*/write*/insert*` + 2 个 settle + 2 个 lock），调用点直接写 `support.xxx` / `taskLocker.xxx` |
| 第 6 刀 | **267 / 18** | `ApprovalOrchestrator`（228 / 2）：`approveInternal`（两个重载）+ `rejectInternal` 作为**一个整体**搬走 |

### 六点一、与建议方向的偏差（都有意为之）

1. **`advanceParallelBranch` 选"留在引擎当门面"**，实现进 `NodeLander`。理由：它和
   `resolveAndLand*` 共用 `pickHandler` / `NodeOutcome` 循环，必须同进同出；而调用方
   （`V2DecisionSettler` / `AutomationCompleter`）只认 `FlowAdvancer`。若把 `NodeLander` 直接
   暴露给结算类，`FlowAdvancer` 就名存实亡，且 `forceApproveAsSystem` 仍在引擎上，接口会裂成两半。
   代价是每次并行推进多一次方法调用，收益是"结算类只认识一个回调口"这条约束不变。

2. **第 6 刀没有只切 `approveInternal` 的推进段，而是把 `approveInternal` + `rejectInternal`
   整体搬到 `ApprovalOrchestrator`**。理由：两者是镜像路径（同样锁任务 → 同样写一条历史 →
   同样按 V2/V1 分流 → 同样推进），只搬其中一段会让"先锁后校验"的规则在两处各留半条。
   这不是把编排拆成碎片——恰恰相反，是把"顺序"集中到一个 228 行的类里；V1/V2 的差异仍在两个
   Settler，推进仍在 `NodeLander`。不这么做的话 ProcessEngine 会停在 473 行，仍 >400。

3. **`ProcessEngine` 的 14 个构造参数不再落成字段**。它们现在只用于装配协作者；保留字段会让人
   以为引擎还在直接写库。构造器签名（14 参数）保持不变，`src/test` 的 4 处 `new ProcessEngine(...)` 零改动。

4. **`LockedTask` 变成文件级 `record`（放在 `TaskLocker.java` 里）**，而不是嵌套类：它同时被审批、
   驳回、返工重提、管理改派传递，嵌套会让某一方"借用"另一方的内部类型。

### 六点二、逐字一致性怎么核的

用 token 级对比脚本（`backend/target/tmp-verify/verify_verbatim.py`，临时文件，未入库）：
从 `git show HEAD:...ProcessEngine.java` 里按"方法声明"提取 17 个方法体，对搬迁后的实现做
**去注释 + 去空白 + 分词**比较，只应用事先列出的重命名（`runtimeV2`→`v2`、
`readTree`→`support.readTree`、`lockPendingTask`→`taskLocker.lockPendingTask` 等）。
17/17 全部一致（`start` 763 token、`approve` 902、`reject` 709、`resolveAndLandLoop` 602、
`advanceParallelBranch` 533、`applyNodeFieldEdits` 514 ……）。任何多/少一个 token 都会报 FAIL。

唯一的**文本级**非前缀改动：`FieldEditApplier` 把 `runtimeV2 == null ? null : runtimeV2.formSchema(pi)`
先取成局部 `v2`；`ProcessStarter` / `ApprovalOrchestrator` 同理在方法入口取一次。字段本身不会在
一次调用中途被替换（测试只在构造后、调用前替换），语义等价。

### 六点三、验证结果

| 项目 | 结果 |
| --- | --- |
| `mvn -B compile` | 通过 |
| `mvn -B test -Dtest='ProcessEngine*'` | 33 例，0 失败 0 错误（每刀都跑过） |
| 全量（排除 PG 集成 + 契约快照） | **660 例，0 失败 0 错误，2 跳过** |
| `node scripts/health-metrics.mjs` | 退出码 0；H7 后端超标类 16 → **13**，ProcessEngine 不在待拆清单里 |
| `git diff --stat -- frontend/config/antflow-api.json` | 空 |
| `src/test` 改动 | 0 |

### 六点四、剩余风险

- **V2 仍只有集成测试保护**（本地无 Docker，`PostgresTransactionalIntegrityIntegrationTest` 跑不了）。
  这次搬迁对 V2 路径的保护只有两条：token 级逐字一致 + 编译 + V1 单测。合并前必须让 CI 跑 PG 集成。
- `NotificationEvent.source` 的可见差异扩大了：`INSTANCE_STARTED` / `INSTANCE_APPROVED` /
  `TASK_ASSIGNED` 的 source 也从 ProcessEngine 变成了 `ProcessStarter` / `NodeLander`。
  全仓无 `getSource()` 读取方（已 grep 确认），类型、接收人、文案均未变。
- 拆分后的类体积：最大 `NodeLander` 267 行 / 7 方法、`ApprovalOrchestrator` 228 行 / 2 方法，
  全部低于 400 行 / 25 方法门禁；但 `NodeLander` 已接近"一个类装两种推进（串行 + 并行）"的上限，
  下次若要动并行语义，建议先补一个并行分支的单测再动。


## 七、WorkflowRuntimeV2 拆分计划

原类：`backend/src/main/java/com/antflow/engine/WorkflowRuntimeV2.java`，**1236 行 / 77 方法**
（H7 口径实测），是当前最大类，也是 V2 运行时表（`t_process_node_instance` / `t_parallel_branch_state`
/ `t_node_participant` / `t_workflow_outbox`）的唯一直接 JDBC 写入方；全仓唯一一条
`UPDATE t_process_instance` 原始 SQL 也在它里面（`recallApproval`，第 768 行）。

### 7.1 方法簇清单（按"变化原因"聚类，不按行号）

| 簇 | 方法 | 净行数 | 变化原因（什么时候会改它） |
| --- | --- | --- | --- |
| A 查询 | `active` `processTree` `formSchema` | 25 | 版本树/表单 Schema 的读取口径变了才改 |
| B 实例生命周期 | `prepareStart` `createRevision` `beginRound` `withdrawToRework` `taskSkippedByWithdraw` `invalidateApprovedNodes` `terminate` `recallApproval` | 172 | 轮次边界、终止/撤回/失效的收口规则 |
| C 节点实例状态 | `requireTransaction` `enterNode` `completeNode` `requireActiveTask` `gatewayNodeInstance` `lockNode` | 89 | 节点实例状态机（ACTIVE/PASSED/AUTO_PASSED/CANCELLED）与 attempt_no 语义 |
| D 决策计算 | `approve` `rejectVote` `rejectAdditional` `activateNext` `scheduleSequentialTimeout` `insertTimeoutUnavailableHistory` `cancelPending` `counts` | 176 | 审批模式（ANY/ALL/RATIO/SEQUENTIAL）与票数规则 |
| E 并行分支 | `registerParallelBranch` `parallelBranchPassed` `parallelBranchRejected` `branchCounts` `cancelGatewayWork` | 116 | 并行 join 语义（ALL/ANY）与分支取消范围 |
| F 字段与审批人解析 | `assignments` `previewAssignments` `bindTask` `scheduleTimeout` `fieldUsers` `ccUsers` `fallbackUsers` `fallbackUnavailable` `requiresNodeFallbackPolicy` `shouldAutoPass(+Preview)` `shouldSkipResubmittedNode(+Preview)` `changedFieldsDoNotAffect` `activeAgent(+ForForm)` `activeUsers` `roleUsers` | 263 | 委派、兜底、自动通过、DIFF_CONTINUE 的判定规则 |
| G 纯策略解析 | `mode` `joinMode` `settings` `required` `hasNodeFallbackPolicy` `readIds` `addLong` `timeoutAt` | 43 | 流程树 JSON 的字段名/默认值（无状态、可直接单测） |
| H 任务参与与通知 | `outbox`(2 重载) `recordCc` `timeoutReminder` `timeoutEscalate` `copyRuntimeTask` `reassignParticipant` `requireActiveAssignee` `recallChild` `addParticipant` `activateAfterSign` `completeBeforeSign` `adminReassign` | 239 | 加签/改派/超时/抄送的任务级协作规则 |

### 7.2 依赖与调用关系

**谁调它（全部是反向依赖，签名必须冻结）**

| 调用方 | 用到的方法 |
| --- | --- |
| `ApprovalOrchestrator`（approve/reject 编排） | `active` `completeBeforeSign` `activateAfterSign` `approve` `rejectVote` `rejectAdditional` `mode` |
| `V2DecisionSettler`（V2 结算） | `parallelBranchRejected` `invalidateApprovedNodes` `beginRound` `createRevision` `outbox` `mode` `Decision` `ParallelDecision` |
| `NodeLander`（节点落地） | `enterNode` `completeNode` `parallelBranchPassed` `outbox` `active` |
| `ApprovalHandler` / `CcHandler` / `ParallelHandler` | `assignments` `bindTask` `scheduleTimeout` `fieldUsers` `ccUsers` `fallbackUsers` `fallbackUnavailable` `requiresNodeFallbackPolicy` `shouldAutoPass` `shouldSkipResubmittedNode` `outbox` `recordCc` `registerParallelBranch` `parallelBranchPassed` `enterNode` `completeNode` `active` `mode` |
| `ProcessStarter` / `ReworkService` / `WithdrawService` / `AutomationCompleter` / `FieldEditApplier` / `TaskLocker` / `EngineSupport` / `AdminActions` | `prepareStart` `outbox` `beginRound` `createRevision` `withdrawToRework` `taskSkippedByWithdraw` `timeoutReminder` `timeoutEscalate` `formSchema` `createRevision` `requireActiveTask` `processTree` `recallApproval` `adminReassign` `terminate` `active` |
| `task.TaskOperationService`（边界外，不改） | `copyRuntimeTask` `reassignParticipant` `requireActiveAssignee` `recallChild` `addParticipant` |
| `mobile.ApprovalPreviewService`（边界外，不改） | `active` `processTree` `formSchema` `shouldSkipResubmittedNodePreview` `shouldAutoPassPreview` `previewAssignments` `fieldUsers` `fallbackUsers` `fallbackUnavailable` `requiresNodeFallbackPolicy` `mode` `Assignment` |

**它调谁（正向依赖，只能指向新协作者）**：`JdbcTemplate`、`DefinitionVersionRepository`、`ObjectMapper`、
`ProcessTreeNav`（并行 join 查网关）、`NodeContext`/`FormData`/`ProcessInstance`/`TaskEntity` 等 DTO。

**公开契约（46 个方法 + 3 个类型）一律不动**：方法名、参数、返回类型、`static` 修饰、
嵌套类型全限定名（`WorkflowRuntimeV2.Decision` / `.Assignment` / `.ParallelDecision` / `.StartState`）
都不能改——`task`/`mobile` 两个包在边界外按名调用，改一个签名就连锁破坏。

### 7.3 拆分后的类清单

`WorkflowRuntimeV2` 保留为**门面**：字段仍只有 `jdbc` / `versions` / `json` 三个 Spring bean，
构造器里按依赖顺序 new 出下列协作者（不注册为 bean，也不加 `@Transactional`）；46 个公开方法全部
改为一行委托。协作者之间只允许正向依赖（下表"构造依赖"列），**没有一个协作者持有
`WorkflowRuntimeV2` 字段或参数**。

| 类 | 职责 | 估算行 / 方法 | 构造依赖 |
| --- | --- | --- | --- |
| `V2RuntimeSupport` | 事务守卫、`active` 判定、版本树/表单 Schema 查询、`outbox`、节点行锁、表单修订创建 | ~120 / 7 | `JdbcTemplate` `DefinitionVersionRepository` |
| `NodePolicies` | 纯函数：`mode` `joinMode` `settings` `required` `readIds` `addLong` `timeoutAt` 等 | ~90 / 9 | 无（全 static） |
| `NodeInstanceState` | 节点实例显式状态迁移：`enterNode` `completeNode` `requireActiveTask` `gatewayNodeInstance` | ~130 / 4 | `V2RuntimeSupport` `JdbcTemplate` |
| `ApprovalParticipants` | 审批人/抄送人解析、委派、兜底、自动通过、DIFF_CONTINUE、`bindTask` `scheduleTimeout` | ~330 / 15 | `V2RuntimeSupport` `JdbcTemplate` `ObjectMapper` |
| `DecisionCalculator` | 票数计算与决策：`approve` `rejectVote` `rejectAdditional` + 顺序激活/取消/超时 | ~235 / 8 | `V2RuntimeSupport` `NodeInstanceState` `JdbcTemplate` `ObjectMapper` |
| `ParallelBranchRuntime` | 并行分支注册/通过/驳回、分支计数、网关清理 | ~175 / 5 | `V2RuntimeSupport` `NodeInstanceState` `JdbcTemplate` |
| `TaskParticipationOps` | 加签前后、撤回加签、改派、抄送记录、超时提醒/升级 | ~290 / 11 | `V2RuntimeSupport` `JdbcTemplate` |
| `InstanceLifecycleOps` | 轮次/终止/撤回/失效/追回、`prepareStart` | ~240 / 7 | `V2RuntimeSupport` `ProcessInstanceWriter` `JdbcTemplate` `DefinitionVersionRepository` |
| `ProcessInstanceWriter` | **唯一**写 `t_process_instance` 的入口 | ~45 / 1 | `JdbcTemplate` |
| `WorkflowRuntimeV2`（门面） | 46 个委托 + 4 个嵌套类型 | ~300 / 50 | 上述 9 个 |

依赖方向（无环）：

```
WorkflowRuntimeV2（只有委托，不写任何 SQL）
  ├─ V2RuntimeSupport ── jdbc / versions
  ├─ NodePolicies（无依赖）
  ├─ NodeInstanceState ── support, jdbc
  ├─ ApprovalParticipants ── support, policies, jdbc, json
  ├─ DecisionCalculator ── support, nodeState, policies, jdbc, json
  ├─ ParallelBranchRuntime ── support, nodeState, policies, jdbc
  ├─ TaskParticipationOps ── support, jdbc
  └─ InstanceLifecycleOps ── support, instanceWriter, jdbc, versions
        └─ ProcessInstanceWriter ── jdbc
```

### 7.4 唯一写入方怎么保持

`t_process_instance` 的原始 SQL 写入**只保留一处**：`ProcessInstanceWriter.repointCurrentNode`。
`InstanceLifecycleOps.recallApproval` 把它原来内联的 `UPDATE t_process_instance SET current_node_id…`
换成一次调用；`WorkflowRuntimeV2.recallApproval` 只委托给 `InstanceLifecycleOps`，自身不写库。
三条可核验的约束：

1. 全仓 `grep -rn "UPDATE t_process_instance" backend/src/main/java` 只命中 `ProcessInstanceWriter` 一个文件；
2. `WorkflowRuntimeV2` 里不再出现任何 `jdbc.update` / `jdbc.query`（只做委托），因此不可能"两处都能写"；
3. 其余实例状态（status/finishedAt/currentNodeId）仍走既有的 `processInstanceMapper.updateById`，
   调用方（`NodeLander` / `ProcessStarter` / `V2DecisionSettler`）一行不改——那不是本次搬迁引入的第二写入方，
   而是原有 MyBatis 路径，动它反而会改变行为。

节点实例表（`t_process_node_instance`）的写入按簇归属明确到 4 个方法：`NodeInstanceState.enterNode`
（INSERT）、`.completeNode`（单行 UPDATE）、`ParallelBranchRuntime.cancelGatewayWork`（分支范围取消）、
`InstanceLifecycleOps.{terminate,withdrawToRework,beginRound,recallApproval,invalidateApprovedNodes}`
（实例范围批量取消）。不新增第五个归属，也不把这些 SQL 复制到门面。

### 7.5 风险与不拆的部分

- **V2 只有集成测试保护**（`PostgresTransactionalIntegrityIntegrationTest`，本地无 Docker）。
  所以本次纪律是：方法体逐字不动，只允许 ① 改可见性 ② 加协作者前缀 ③ 把 `outbox/completeNode/...`
  改成 `support.xxx` / `nodeState.xxx`。任何"顺手优化"都不做。
- **不把 4 个嵌套 record/enum 提为顶层类型**：外部以 `WorkflowRuntimeV2.Assignment` 等全限定名引用
  （`task`/`mobile` 在边界外），提成顶层必然改签名。因此新协作者会引用这些**类型**，但不会引用
  `WorkflowRuntimeV2` **实例**——这不构成反向依赖（没有字段、没有构造参数、没有回调）。
- **不拆 `requireTransaction` 的失败语义**：保持 `IllegalStateException` + 原文案，只把位置移到
  `V2RuntimeSupport` 并改为包内 static；不加 `@Transactional`，事务边界仍在 `ProcessEngine` 入口。
- **不合并 `assignments` 与 `previewAssignments`**：一个写参与者表、一个只算不写，
  合并会让"预览无副作用"这条约束消失（移动端预览直接调）。
- **不动 `enterNode` 的网关查询**：它按 `(proc_inst_id, node_id, round_no, status='ACTIVE')`
  + `attempt_no DESC` 取最近一次尝试，与 `attempt_no` 自增语义耦合；换写法就可能在重试轮次里拿错行。
- **不改 `shouldSkipResubmittedNode` 的异常吞噬**：`catch (Exception ignored) { return false; }`
  是有意的"差异算不出来就不跳过"，改成抛错会让重提直接失败。

### 7.6 执行记录（2026-10-22，七刀全部落地）

每刀后都跑 `mvn -B -q compile` + `mvn -B test -Dtest='ProcessEngine*'`，**33 例全绿**。
行数/方法数按 `scripts/health-metrics.mjs` 的 H7 口径。

| 阶段 | 门面行 / 方法 | 本刀产出（行 / 方法） | 职责 |
| --- | --- | --- | --- |
| 拆前 | 1236 / 77 | — | — |
| 第 1 刀 | — | `V2RuntimeSupport`（98 / 8）、`NodePolicies`（80 / 9） | 事务守卫、V2 判定、版本/Schema、outbox、行锁、修订；纯策略解析 |
| 第 2 刀 | — | `NodeInstanceState`（119 / 4） | 进入/完成/有效性校验/网关定位 |
| 第 3 刀 | 877 / 71 | `ApprovalParticipants`（331 / 18） | 审批人/抄送/兜底/委派/自动通过/DIFF_CONTINUE |
| 第 4 刀 | 697 / 63 | `DecisionCalculator`（243 / 11） | 票数、顺序激活、取消、顺序超时 |
| 第 5 刀 | 592 / 57 | `ParallelBranchRuntime`（165 / 6） | 分支注册/join/跨分支清理 |
| 第 6 刀 | 433 / 51 | `TaskParticipationOps`（244 / 11） | 抄送记录、超时、加签、改派 |
| 第 7 刀 | 290 / 50 | `InstanceLifecycleOps`（222 / 7）、`ProcessInstanceWriter`（35 / 1）、`TaskRow`（9 / 0） | 轮次/终止/撤回/失效/追回；唯一主表写入 |
| 收尾（删字段/导入） | **277 / 50** | — | 门面只剩委托 + 4 个嵌套类型 |

**唯一写入方核验**：`grep -rn "UPDATE t_process_instance" backend/src/main/java` 只命中
`ProcessInstanceWriter.java:30`（另一处是它自己的 javadoc）；门面里 `grep "jdbc\."` 为 0 命中，
只剩一行注释。节点实例写入归属仍然是 4 处（`enterNode` / `completeNode` / `cancelGatewayWork` /
生命周期批量收口），没有新增。

**逐字一致性**：`backend/target/tmp-verify/verify_v2_verbatim.py`（临时，不入库）对 69 个方法体做
token 比较（去注释、去空白、归一化协作者前缀与 `active`→`isV2`），**69/69 一致**。
唯一的声明改写是 `recallApproval`：把内联的 `UPDATE t_process_instance …` 换成
`instanceWriter.repointCurrentNode(...)`；脚本剔除这一条语句后比较，其余 token 完全一致（343 → 327 token）。
公开签名用同样的脚本比对：拆前拆后 **49 个公开声明完全一致**（无新增、无缺失）。

**验证**：`mvn -B compile` 通过；`ProcessEngine*` 33 例 0 失败；全量（排除 PG 集成 + 契约快照）
**660 例，0 失败 0 错误，2 跳过**；`node scripts/health-metrics.mjs` 退出码 0，后端 >400 行超标类
16 → **11**，最大类 811 行 / 67 方法（已不是本类）；`frontend/config/antflow-api.json` diff 为空。

### 7.7 与计划的偏差（都有意为之）

1. **`ApprovalParticipants` 没有注入 `V2RuntimeSupport`**：这一簇只用 `jdbc` / `json` 与静态
   `V2RuntimeSupport.isV2`，多注入一个字段只会让它看起来"能写 outbox"。计划表里的依赖估算是保守值。
2. **`TaskRow` 提升为包级 `record`（单独文件）**：它被决策、并行、任务参与、生命周期四簇共用；
   留在门面里会让四个新类都无法引用，留在某一簇则形成跨簇反向依赖（与 `LockedTask` 同一处理方式）。
3. **`Participant` / `Counts` / `BranchCounts` 跟着各自的簇走**（`DecisionCalculator`、`ParallelBranchRuntime`）：
   它们是私有实现细节，不与外部契约耦合，没必要集中。
4. **门面仍是 50 个方法（>25）**：46 个公开方法必须留在门面上（`task` / `mobile` 在边界外按名调用），
   这不是"没拆干净"，而是冻结签名的必然结果。行数已从 1236 降到 277，不在 >400 清单里。
5. **`@Slf4j` 从门面移走**：日志只发生在 `DecisionCalculator.scheduleSequentialTimeout`，
   门面不再需要 logger（定时清理时也不会留下空 logger 字段）。

### 7.8 剩余风险

- **V2 路径仍只有 PostgreSQL 集成测试覆盖**（本地无 Docker，跑不了）。本次的保护是：69 个方法体
  token 级一致 + 33 例 V1/树遍历单测 + 全量 660 例 + 编译。合并前必须让 CI 跑
  `PostgresTransactionalIntegrityIntegrationTest`，重点看四条：`assignments`（参与者写入）、
  `approve` 的 SEQUENTIAL 分支（新任务 + 超时作业）、`parallelBranchPassed`（ANY 收口）、
  `recallApproval`（唯一主表写入）。
- **`recallApproval` 是唯一有语句级改写的路径**：`ProcessInstanceWriter` 的方法体与原 SQL 完全相同，
  但"调用点从内联变成方法调用"这件事只有集成测试能验证（参数顺序 `procInstId, nodeId, nodeInstanceId`
  已逐字对齐原语句的 `task.getProcInstId(), task.getNodeId(), task.getNodeInstanceId()`）。
- **门面委托多一层栈帧**：异常堆栈里会出现 `WorkflowRuntimeV2.xxx` → `XxxOps.xxx` 两行；
  错误码与文案未变。若有基于堆栈行号的监控，需要知道这一层。
- `TaskRow` 变成包级类型后，`com.antflow.engine` 包内新增了一个可被同包误用的名字；
  它只有 `id/assigneeId` 两个字段，误用不会造成状态泄漏。
