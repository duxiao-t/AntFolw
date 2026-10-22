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


