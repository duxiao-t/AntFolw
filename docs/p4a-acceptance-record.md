# P4a 验收记录（公司归属快照 / V54）

> **状态：规则层与写路径层已验收；上线验收与历史草稿回填未完成**（见 §4）。
> 记录时间：2026-10-14。代码基线：`HEAD = f456beb`（此后只改文档，见 §3）。
> 验收口径来源：`DECISIONS.md:546`。上线步骤来源：`docs/p4a-rollout-runbook.md`。

## 0. 验收口径（三条，逐条给出证据）

`DECISIONS.md:546` 定的 P4a 验收标准：

1. **组织变化不改旧归属** —— 调岗/部门变动不改变已记录的历史归属。
2. **新写入不漏快照** —— 每条新记录都带明确归属（实例创建时快照，草稿自己存）。
3. **无公司的新记录不存在** —— 要么有明确公司，要么显式"待确认"（`company_id IS NULL` 且**保留**），不允许静默写成空。

三态定义（`HANDOFF.md:86-87`）：`(非空,false)` 已确认快照 / `(非空,true)` 历史推断 / `(NULL,false)` 待确认
——**`NULL` 行的 `false` 不代表"已确认"**。

---

## 1. 口径 1：组织变化不改旧归属

| 层 | 证据 | 断言 |
| --- | --- | --- |
| 规则 | `backend/src/test/java/com/antflow/org/CompanyAttributionTest.java:144-152` | 用户从 8 号部门（公司 20）来提交 9 号公司建的草稿 → 仍归 9；且 `verifyNoInteractions(departmentMapper)`（草稿路径**不查任何部门**） |
| 真库 | `PostgresTransactionalIntegrityIntegrationTest.java:367` `draftSubmittedAfterTheAuthorTransferredKeepsTheDraftCompany` | 作者从甲部（公司 A）调到乙部（公司 B）后提交原草稿 → `companyOfFormData == companyA`、`companyInferred == false`、草稿行被消费 |
| 真库 | 同文件 `:448` `approvingATaskDoesNotRewriteTheRecordedCompany` | 走完整审批后 `t_form_data.company_id` 与 `t_process_instance.company_id` 均为原值 |
| 守护 | `HANDOFF.md:112-113` | 把草稿路径改回 `resolve()` → 移动单测红；删掉"有部门的人传别家公司"分支 → `CompanyAttributionTest` 红 |

## 2. 口径 2：新写入不漏快照

写路径清点（`DECISIONS.md:527-533`，这是"P4a 不只是加一列"的实证）：

| 记录 | 写入口 | 归属来源 |
| --- | --- | --- |
| `t_process_instance` | **唯一**：`ProcessEngine.java:133` | 创建时快照 |
| `t_form_data`（引擎路径） | `ProcessEngine.java:111`，同一 `@Transactional` | 从实例继承 |
| `t_form_data`（无流程提交 / 草稿） | `FormDataService.java:105`、`MobileDraftService.java:51` | **自己快照**（无实例可继承） |

证据：

- `backend/src/test/java/com/antflow/mobile/workflow/MobileDraftServiceTest.java:86` —— 保存草稿后 `saved.getCompanyId()` 非空。
- `backend/src/test/java/com/antflow/mobile/workflow/MobileWorkflowServiceTest.java:138-140` —— 草稿转正时
  `StartCmd.companyId == 42L`（**取草稿行的快照**），且 `verify(companyAttribution, never()).resolve(any())`。
  这条就是 `HANDOFF.md:112` 的 `expected 42L but was 0L` 守护。
- 真库 `PostgresTransactionalIntegrityIntegrationTest.java:329` `directSubmissionConsumesOnlyItsSourceDraft`。
- `PrincipalHolder` **没有** `companyId`（`auth/PrincipalHolder.java:9-11`）⇒ 归属只能来自显式解析或快照，
  不存在"照着当前身份偷偷补一个"的路径。

## 3. 口径 3：无公司的新记录不存在

规则层一律 **fail-closed**（`CompanyAttributionTest.java`）：

| 场景 | 用例 | 结果 |
| --- | --- | --- |
| 部门没有公司 | `departmentWithoutCompanyFailsClosedInsteadOfWritingNull:70` | `BAD_COMPANY`（**不写 NULL**） |
| 部门被删、身份还挂旧 id | `deletedDepartmentFailsClosed:80` | `BAD_COMPANY`（不降级成"无部门"放行） |
| 无部门的普通账号 | `departmentlessNonAdminCannotCreateAtAll:124` | `BAD_COMPANY`，且 `verifyNoInteractions(companyMapper)` |
| 无部门管理员不指定公司 | `departmentlessAdminMustNameACompany:93` | `BAD_COMPANY` |
| 无部门管理员指定不存在的公司 | `departmentlessAdminNamingAnUnknownCompanyIsRejected:102` | `BAD_COMPANY` |
| 有部门的人指定**别家**公司 | `departmentUserCannotBeBookedIntoAnotherCompany:51` | `BAD_COMPANY`（请求参数不能当身份用） |
| 无身份 | `missingPrincipalIsAnAuthenticationFailureNotABusinessError:135` | `AccessDeniedException`（不是业务错） |

真库 + 活栈：

- `PostgresTransactionalIntegrityIntegrationTest.java:417` `legacyDraftWithoutCompanySnapshotIsBlockedAndKept`
  —— 历史草稿（`company_id IS NULL`）提交 → `COMPANY_PENDING`；**草稿保留**、`SUBMITTED` 计数为 0（整笔回滚）。
- **活栈实测（2026-10-14，本机 docker 栈）**：草稿 43（`company_id NULL`，表单已升版 11→13）在移动端
  显式选公司后，`PUT /api/mobile/drafts/43` 仍返回 **422 `COMPANY_PENDING`**，`updated_at` 停留在 2026-09-22
  **一行未写**。⇒ 请求里给公司也救不了 NULL 草稿（`forDraft` 先查 `storedCompanyId`，`CompanyAttribution.java:71-77`），
  且**保存**路径（`MobileDraftService.update`）同样被挡。

---

## 4. 验证基线（2026-10-14 实测）

串行跑（**不并发**——并发会让前端 `app.test.tsx` 假红，`HANDOFF.md:71-73`）；后端先重建探针库
`antflow_p4a_probe`（PG 17.10，`MakeProbeDb.java`），再带 `ANTFLOW_TEST_POSTGRES_URL` 起跑。

| 套 | 命令 | 结果 | 退出码 |
| --- | --- | --- | --- |
| 后端 | `mvn -B test` | `Tests run: 601, Failures: 0, Errors: 0, Skipped: 2` | 0 |
| 前端 | `npm run test` | `Test Files 64 passed (64)`、`Tests 314 passed (314)` | 0 |
| 移动端 | `npx vitest run` | `Test Files 46 passed (46)`、`Tests 362 passed (362)`、`Errors 1 error` | **1** |

两处要说清，免得读混：

- **前端第一次跑是红的 1 条**：`src/app.test.tsx:56` `should fetch currentUser when not on login page`
  30s 超时（`import('./app')` 会拉起 iframe `localhost:3000/mobile/form-preview`）。单独重跑即
  **314/314、退出码 0**——与 `frontend/vitest.config.ts:37-39`、`HANDOFF.md:71-73` 记的 flake 一致。
- **移动端的「362 绿」指用例数，不是退出码**：`362/362` 全过，但 `npx vitest run` **退出码是 1**，
  来自 1 条**既有** unhandled error `unmountComponentAtNode is not a function`
  （`src/features/forms/fields/fields.test.tsx`；rc-util / antd-mobile Popup × React 19 卸载路径）。
  这条在 `HANDOFF.md:649`、`:276` 已记载为既有、与本轮改动无关，**不是回归**。

**这些数字对当前代码成立**：代码停在 `f456beb`（`git diff --stat f456beb HEAD` 为空），
工作区只有 `docs/` 改动。

## 5. 活栈三项手点（2026-10-14，本机 docker 栈）

| 项 | 证据 |
| --- | --- |
| 无部门管理员能选公司 | `admin`（`dept_id NULL`）建草稿 52 → `company_id=1, company_inferred=f, created_by=1` |
| 普通用户自动推导公司 | `test1`（`dept_id=4 信息部`）提交，客户端发 `companyId:null`，服务端写 → 实例 `company_id=1, started_dept_id=4` |
| 草稿刷新后归属不丢 | 草稿 52 reload（`?draftId=52`）→ 选择器变只读「本草稿的归属已固定为 AntFlow」，`company_id=1` 原样保留 |

---

## 6. 尚未验收 / 未完成（必须写清，别把本记录读成"P4a 已上线"）

1. **上线验收签字：未完成。** 窗口定为 **2026-10-15 02:00（+08:00）**，bundle 执行 `p4a-rollout-runbook.md` §3，
   验收动作在 §4。本记录只覆盖"代码 + 规则 + 活栈功能"三块。
2. **窗口时长未实测。** §3.1 要求按完整 `Flyway migrate` 估，但生产量级预演**本轮未跑**（缺生产 `pg_dump`）
   ⇒ runbook 里的 **30 分钟是估值**，不是量出来的数。
3. **历史草稿 3 条未回填**（`t_form_data.status='DRAFT' AND company_id IS NULL AND 无实例`）。回填前这几位用户在移动端
   **存和提交都 422**（=`draft handler` 的 §6.2，上线后要立刻做）。
4. **公司 / 部门归属没有可用的管理入口**（见 `HANDOFF.md` 已知遗留）——今天只有 1 家公司所以没暴露；
   要支持多公司必须先补入口。
5. 本片**不改读权限**：`company_id` 目前不参与数据范围收敛（那是 P4 的 #3/#5）。

## 7. 结论

口径 1/2/3 在**规则层、写路径层、真库层、活栈层**四层均有证据，且都有"去掉修复即失败"的守护用例
⇒ **代码与规则部分验收通过**。P4a 的剩余部分是**运维动作**（窗口上线 + 历史草稿回填），
不在本记录的覆盖范围内。
