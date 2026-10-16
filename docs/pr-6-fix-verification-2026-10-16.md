# PR #6 已确认问题修复与验证

验证日期：2026-10-16，Asia/Shanghai。修复基线为 `a6335d6`，分支为
`feat/architecture-completion`，PR 目标仍为 `master`。本次只处理三个已确认问题及直接关联逻辑，
没有新增依赖、数据库迁移或改写历史迁移。

## 1. 强制审批未同步参与人投票：验证通过

**触发与复现：** V2 ALL/RATIO 节点有两名审批人，管理员打开其他人的任务，通过
“紧急介入”执行同意或驳回。原实现用管理员 `operatorId` 匹配参与人 `actual_user_id`，
SQL 更新零行，任务已完成而投票仍为 PENDING；ALL/RATIO 无法结算。加签驳回也存在同类错误。
这是 `master` 已有的问题。

**修改：** `WorkflowRuntimeV2.approve/rejectVote/rejectAdditional` 按任务的
`assigneeId + nodeInstanceId + sequenceNo` 记票，校验恰好更新一行；不匹配抛出
`BAD_TASK_STATE`，同一事务回滚任务及历史。`ProcessEngine` 继续将真实操作者写入
`approvedBy` 和审批历史，不再向记票方法传入容易混淆的操作者参数。

**浏览器步骤与结果：** 真实 Chrome，桌面 1440×1000、移动 390×844，无接口 mock。

1. 创建两名审批人的 ALL 流程；管理员在桌面打开实例，填写工单与原因，强制同意第一人的任务。
   真库核对任务与参与人均为 APPROVED，assignee 仍为原审批人，approvedBy 为管理员。
2. 第二人在移动端打开待办，点“同意→确认同意”。实例正常 APPROVED，桌面显示“已通过”。
3. 创建通过阈值 50% 的双人 RATIO 流程；管理员先强制同意第一人，再强制驳回第二人。
   两票完成后按 50% 阈值正常通过，没有遗留 PENDING 投票。
4. 对已处理任务重复请求紧急同意，返回预期的 422，原结果不改变。

最后一次完整浏览器运行的实例为隔离库中的 7（ALL）与 8（RATIO）。
另有真库回归覆盖 ALL/RATIO 强制驳回后退回修改、前/后加签强制驳回，以及参与人不匹配时
任务和历史一并回滚。现有或签、并行、顺序签、委托、超时、追回等用例随全量后端检查执行。

## 2. 历史只读值阻断重提及真实调用丢值：验证通过

**触发与复现：** 存量 switch 字段保存字符串 `"true"`，ROOT 权限为 READONLY；
单据被退回后，发起人修改其他字段并重提。新增严格形状校验会拒绝这类历史值，
用户无法修正只读字段。浏览器还确认移动填写页会在请求之前做同样的全字段校验。

真实集成回归同时发现：持久化数据以 JSON 字符串传入 `canonicalizeStarterRevision`，
旧 `valueMap` 只识别 Map/JsonNode，导致受限历史值在保存或重提时被丢弃。

**修改：**

- 后端先解析已持久化 JSON，再保留 READONLY/HIDDEN 原值，忽略客户端对这些字段的改写。
- 新增 `validateStarterRevision`，只校验 ROOT 可编辑字段；重提预览与正式重提共用这一口径。
  初次提交仍走原严格校验，不豁免 READONLY 默认值。
- 移动填写页仅在重提时将字段模式传给原校验器；校验器在明细行中递归应用相同权限，
  保留只读字段参与显示条件判断；可编辑字段仍执行必填、形状和行数约束。

**浏览器步骤与结果：**

1. 发起专用测试流程，在隔离库将只读 switch 改为历史字符串 `"true"`，由审批人驳回到发起人。
2. 发起人通过移动填写页修改“修改说明”，点击“提交”，成功打开确认页并取得审批预览。
3. 点击“确认重提”，正式接口返回 200，页面显示“提交成功”。
4. 真库核对仍为原实例 9、原流水号 `000000000009`，轮次变为 2；
   `legacy` 仍为 JSON 字符串 `"true"`，说明字段已更新，没有用默认值覆盖历史数据。

真实服务回归还验证了：伪造只读值不能覆盖存量值；可编辑 switch 传字符串时，
预览与重提都返回 `FORM_DATA_INVALID`；新的 READONLY 非法默认值仍被拒绝。
移动回归同时覆盖可编辑必填校验、行内历史只读值、全部只读明细和条件显示。

## 3. 容器构建遗漏测试资源：验证通过，浏览器不适用

**触发与复现：** 使用 `backend/build.sh` 的 `/app` 目录布局运行新增共享 fixture
及 BI 权限测试。原挂载只有 backend 和 frontend/config，测试读取
`../fixtures`、`../infra/sql` 时缺文件。另一个直接关联问题是无 PostgreSQL/socket 时
打包仅排除一个集成测试，遗漏同样依赖 PostgreSQL 的 OpenAPI 契约测试。

**修改：** 新增 `/fixtures`、`/infra` 只读挂载；无数据库的打包分支同时排除
`PostgresTransactionalIntegrityIntegrationTest` 和 `ApiContractSnapshotTest`，
显式 `test` 仍明确拒绝在缺少数据库的环境运行。

**验证步骤与结果：**

1. 在真实 Linux Docker/Maven 容器执行修改后的 `build.sh test`，选取字段目录生成物、
   display-text、共享提交校验和读取 BI SQL 的真实 PostgreSQL 权限用例：79 条全部通过。
2. Windows 宿主只在验收辅助脚本中将 Maven 缓存卷映射到本机已有缓存；源码、fixture、
   infra 的相对路径和实际 `build.sh` 挂载逻辑保持一致。
3. Linux 验收副本将 Windows 工作区的混合换行 fixture 转为仓库规定的 LF；
   首次复制原工作区字节时曾因逐字比对换行差异失败，未修改提交中的 fixture 或放宽断言。
4. 通过 Bash 分支断言验证无库打包追加两个排除项、显式 test 返回非零。
   该分支检查截获 Docker 参数，不把它等同于一次无库完整打包。

构建资源问题没有浏览器操作入口；浏览器验证针对本机 Maven 打包的修复后 JAR 与最新移动产物，
不能替代以上真实容器检查。

## 4. 检查与证据

| 检查 | 实际结果 |
| --- | --- |
| 后端全量 `mvn -B package`，全新独立 PostgreSQL | 718 条，0 failure/error，2 条既有 skip，JAR 打包成功 |
| 真实容器 `build.sh test` 相关用例 | 79 条，全部通过 |
| 桌面详情展示与字段权限现有测试 | 2 文件 / 7 条通过 |
| 移动全量测试 | 48 文件 / 447 条通过，退出 0 |
| 移动类型检查、构建、体积预算、lint | 全部退出 0；10 条既有 lint warning、1 条配置弃用 info |

后端两条 skip 为既有应用 smoke 和需环境开关的 MinIO lifecycle；本次没有声称执行它们。
API 契约快照门禁随全量后端检查通过，未改接口 JSON、生成契约或迁移。

真实浏览器最后一次完整运行退出 0：业务 pageerror 0、同域网络异常 0、业务控制台错误 0。
另有一次 `stats.g.doubleclick.net` 外部统计连接关闭及其控制台记录，已单独保留，未修改统计配置。
隔离应用 health 为 UP，入口为本机 `127.0.0.1:7075`，未替换 7070 的既有应用。

本机证据在 gitignored 的 `backend/target/`，不包含在 PR：

- `fix-backend-final.log`、`fix-container-final.log`、`fix-mobile-final.log`、
  `fix-mobile-build.log`、`fix-mobile-lint.log`、`fix-mobile-bundle.log`、`fix-desktop-tests.log`。
- `fix-browser/result.json` 及 `all-normal-approval.png`、`all-settled.png`、
  `ratio-settled.png`、`legacy-rework-success.png`。
- 修复前回归失败记录：`fix-votes-before.log`（3 条失败）、
  `fix-validation-before.log`（2 条失败）、`fix-mobile-before.log`（3 条失败）。

## 5. 未覆盖部分

企微慢 outbox 投递阻塞续租仍为待验证风险，未做超过 120 秒的联合积压故障注入，
本次未调整调度器。未执行生产发布、生产数据修复、真实企微/OIDC 企业账号联调，
也未对已有卡住实例自动补写历史投票。若已有受影响业务实例，须先核对任务、参与人和审计后
单独制定数据修复步骤，不能从本次代码验证推断已修复所有历史数据。
