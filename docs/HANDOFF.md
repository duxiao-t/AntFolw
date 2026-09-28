# 任务交接

- **State:** active
- **更新时间：** `2026-09-28T16:20:00+08:00`
- **分支：** `fix/feedback-17`（从 `master` = `63582d5` 拉出，4 个提交，**未推远端、未开 PR**）

## 这一轮做了什么

实测反馈 9 条 + `ocr` 顺手抓到 8 条 = **17 条**，按 4 组提交（一组一个提交，一组一跑三端测试）：

| 提交 | 组 | 内容 |
| --- | --- | --- |
| `a4929fc` | ① | 监控页整页报错（**我自己 A6 的回归**）+ 空范围 fail-open + dataId/instanceId 版本口径 |
| `755175d` | ② | 登录 401 不再整页刷新；节点名显示中文；指定人员不再 400；发起人按 ROOT 判隐藏；详情旧值不刷新 |
| `affbf9e` | ③ | 条件值读外部数据源；删最后一组真的删掉；值 `0` 不再被吞；脏 schema 不再让发布校验崩 |
| `0742b96` | ④ | CC 抄送永不投递（`Map.of` 撞 null）；SSE 注册竞态；绑定报错带字段名；选择器默认当前用户/部门 |

### 每条对应的根因（复验时按这个找）

1. **登录失败整页刷新** —— 登录请求没带 `skipErrorHandler`，401 被 `errorHandler` 与响应拦截器**两处**当成会话失效跳转。两处都加了"已在登录页就不跳"，并识别 `/api/auth/*`（不误清 token）。
2. **`/approval/monitor` 打不开** —— Java 文本块**删掉每行行尾空格**，`AND """ + scope.sql()` 粘成 `AND1 = 1`；只有 admin/unrestricted 会炸（受限范围带括号）。原来那条单测 mock 掉 `JdbcTemplate`，`contains("1 = 1")` 恰好也被 `AND1 = 1` 满足。现在改用 `/*scope*/` 占位 + `replace`，并补了**真库**三范围用例。
3. **节点名显示内部 id** —— 统一走 `ProcessTreeNav.displayNameFromSnapshot`（`props.name → props.title → name → id`；`__rework__` → 「待修改」）。桌面列表用响应里已有的 `processSnapshot` 零后端改动。
4. **绑定报错不带字段** —— `OptionRuntimeService.requireVersion` 带上宿主字段：`字段「X」绑定的数据源版本不存在、未发布或不可绑定`。5 处调用点各传手里的 node，`forBinding` 与 `FOR SHARE OF s` 锁语义**没动**。
5. **指定人员 400** —— axios 0.27 把数组序列化成 `userIds[]=3`，Tomcat 按 RFC 7230 拒裸方括号，请求没进 Spring。改 `join(',')`（对齐移动端）。
6. **条件值不读外部数据源** —— 设计器里绑了 `optionSource` 的字段，条件"值"下拉现在走 `POST /api/runtime/form-options/preview/{formId}`（带设计器里**还没保存**的 schema）取候选；分步（cascade）字段要逐级 drill，保持内置选项。
7. **制单节点隐藏对发起人无效** —— 发起人在 ROOT 没有待办 → 原逻辑拿不到节点 → 权限集为空。现在查看者 == 发起人时叠加 ROOT 的 `formPerms`（EDITABLE 降为只读，避免详情页出现可编辑控件）；返工（REWORK）期间同样按发起人算。
8. **默认当前用户/部门** —— 新增 `props.defaultToCurrent`，语义是**写进表单值**（不是显示兜底，否则不改它直接提交等于空）：桌面在字段组件里按 `mode === 'runtime-fill'` 落值（天然覆盖分栏/明细行），移动端并进 `applySchemaDefaults`。多选只预填 1 个。候选标签带工号 `张三(010025)`。
9. **CC 抄送永远投递失败** —— `CC_ASSIGNED` 没有 taskId，`MobileEventController` 用 `Map.of(...)` 撞 null 抛 NPE → 异常冒回 `WorkflowOutboxDispatcher.deliver` → 重试 10 次进 DEAD（站内通知写了、SSE 永远推不出去）。换成允许 null 的 `LinkedHashMap`；失败日志同时带上异常对象（原来只有 message，NPE 只留一个类名）。

另外顺手（同文件、同类、改动小）：SSE `emitters.compute` 让注册与移除同 key 原子；`requireVersion` 的列映射报错也带字段名；`flattenFormFields` 的容器判断挪到 id 判断之前（没有 id 的容器不再让子字段被静默丢弃）；`groups`/`headers`/`parameters` 非数组按空数组处理。

## 还欠的验证

**已经验掉的**（2026-09-28，本机 docker 栈）：`antflow-local` 上 **Flyway 已到 V49**（V48 哨兵、187 个会话撤销、`v_user_catalog` 不暴露工号；V49 台账 71 行 / 34 张单据、`multi_select` → 「选项3、选项2」）。上一轮的「V48/V49 没走过 Flyway」这条**已关闭**。

**要做/还没做的**：

1. **本轮 17 条的用户可见行为要在部署后逐条复验**（见下面的清单）。
2. **`t_workflow_outbox` 里那 5 条 `CC_ASSIGNED` DEAD 行**：**保持 DEAD，不重投**。它们是修复前的证据，重投等于给用户补发一周前的抄送汇总。复验标准是"**不再新增** CC 的 DEAD 行"。
3. **移动端的第 7 条（制单节点隐藏）没做**：移动任务详情拿不到发起人（`MobileTaskDto` 没有 `applicantId` / `MobileWorkflowMapper` 没查），要补一个字段 + 一条 SQL，属独立的接口改动。桌面端已修。
4. **两套 Playwright e2e**（`frontend`、`mobile`）CI 里不跑，本轮也没跑。

### 复验清单（部署后一条条走）

| # | 操作 | 期望 |
| --- | --- | --- |
| 1 | 登录页输错密码 | 停在登录页，提示可见，**不整页刷新**（redirect 参数不丢） |
| 2 | admin 打开 `/approval/monitor` | 正常出 5 块数据（不再"流程监控数据不可用"）；受限账号看不到外部门数据 |
| 3 | 待办/已发/监控列表看"当前节点" | 中文名（如「部门审批」），不是 `node_adurTht3`；退回待改显示「待修改」 |
| 4 | 打开一个绑了外部数据源的旧表单 | 报错点名 `字段「X」…`（原来只有"数据源版本不存在"） |
| 5 | 用户选择组件，范围选「指定人员」并逐个加人 | 不再 `Response status:400`；空名单零候选 |
| 6 | 流程设计器 → 条件分支 → 选一个绑了外部源的字段 | "值"下拉是**外部数据源的选项**，不是绑定前的内置选项 |
| 7 | 制单（ROOT）节点把字段设为隐藏，发起人提交后看详情 | 该字段对发起人不可见；审批人按自己节点判 |
| 8 | 设计器给用户/部门选择勾「默认填充当前用户/部门」，新填一张表单 | 预填成当前用户/部门且**能提交上**；候选显示 `张三(010025)` |
| 9 | 触发一次抄送 | SSE 到达；`select status,count(*) from t_workflow_outbox where event_type='CC_ASSIGNED' group by 1` **不再新增 DEAD** |
| 13 | 条件分支删掉最后一个条件组 | 组真的消失（空态提示），不再"自己长回来" |
| 14 | 条件值选一个值为 `0` 的选项，再重开 | 仍显示该选项（原来被吞成空） |
| 15 | 绑定报错 / 脏流程导入 | 校验报"配置问题"而不是整页崩 |

## 已知遗留

- **outbox 投递租约可能重复投递**（设计问题，本轮只留档）：`WorkflowOutboxDispatcher` 的 2 分钟租约（`locked_at < now() - interval '2 minutes'`）可以被另一个 worker 重新领取，慢监听器会**重复对外通知**；`locked_by` 只能防状态覆盖，撤不回已经发出去的通知。要修得做"续租"或"每轮 claim 独立令牌 + 下游幂等"。
- **移动端制单节点隐藏**没做（见上，第 3 条）。
- **`HIDDEN` 不是保密边界**：只在渲染层与选项接口收窄，实例详情仍返回隐藏字段的原始值 → 见 `docs/DECISIONS.md` 的 `D-20260928-hidden-field-is-not-a-secrecy-boundary`。
- **前端测试环境关掉了"未处理错误"这层安全网**：`frontend/vitest.config.ts` 里 `dangerouslyIgnoreUnhandledErrors: true` 用来压掉 `MobileFormPreview` 真 iframe 在 happy-dom 下的 `DOMException`（窄口径修法已排除，见配置注释）。断言失败与测试内抛错照旧会红；`testTimeout` 同时从 15s 提到 30s。**移动端 lint 的 13 条既有错误**（`useExhaustiveDependencies` 等）本轮没动。
- 台账的 `table_list` 明细**没按列展开**（会改变视图粒度）；存量角色权限三条（V40 丢 `data_scope`、create/design 合并、rename 无冲突保护）只记录不改。
- **桌面用户/部门选择的"默认填充"与"候选范围"是两件独立配置**：勾了默认填充但范围把本人排除时，下拉会显示成裸 id（值还在，只是没有候选给它做标签）。当前不拦这种组合。

## 验证基线

后端 `mvn -B test` **485**、前端 `npm test` **240**、移动 `npm test` **348**；前端 `biome:lint` 4 warnings（既有）+ `tsc` 干净，移动 `biome lint` 干净（CI 只跑 `biome lint`，不含格式化/导入排序——别用 `biome check --write` 全量刷，会把既有文件重排成大片无关 diff）。本轮新加的守护用例都验过"去掉修复即失败"。

> 上一轮（S1–S4 整改 + 收尾）见 `63582d5` 及其父提交；那轮定下的 D1–D8 决策已落到 `docs/DECISIONS.md` 的 `D-20260921` ~ `D-20260924-*` 条目。
