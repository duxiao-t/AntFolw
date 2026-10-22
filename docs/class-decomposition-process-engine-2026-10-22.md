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

