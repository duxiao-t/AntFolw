# CLAUDE.md

本文件为 Claude Code 在此仓库工作提供指引。

## 这是什么

**AntFlow** —— 一个流程审批后台管理系统（对标钉钉：可视化表单配置 + 审批流配置 + 权限/审计/自动化）。单仓多模块 monorepo：

```
ant-flow/
├── backend/     # Spring Boot 3 + Java 17 + MyBatis-Plus + Flyway(V1–V21) + PostgreSQL + MinIO；自研轻量审批引擎 + RBAC/审计/自动化
├── frontend/    # Umi Max 4 + React 19 + antd 6 + zustand（ant-design-pro 底座）；有自己的 CLAUDE.md
├── mobile/      # 独立移动端 Vite + React + Ant Design Mobile；base `/mobile/`；企业级审批闭环
├── infra/       # nginx 示例
└── docs/        # superpowers/specs、plans；mobile-enterprise-verification.md、session-summary-2026-07-31.md
```

> 注意：`backend/` 与 `frontend/` 各有独立依赖与构建。**先 `cd` 进对应模块再执行命令**，不要在仓库根跑 `mvn`/`npm`。前端另有 `frontend/CLAUDE.md`（Biome-only、TS strict、`/antd` 与 `/pro-upgrade` skill），改前端前先读它。

## 运行 / 构建

```bash
# 数据库与对象存储
# PostgreSQL 17 使用本机服务，库名 antflow。
$env:MINIO_ROOT_USER='minioadmin'
$env:MINIO_ROOT_PASSWORD='minioadmin'
minio.exe server E:\minio-data --address ':9000' --console-address ':9001'

# 后端（首次启动自动跑 Flyway，附件默认写入本机 MinIO）
cd backend && mvn -B spring-boot:run       # http://localhost:8080
cd backend && mvn test                     # 单元测试（不需 PG）

# 前端（dev 代理 /api → :8080）
cd frontend && npm install
cd frontend && npm start                   # http://localhost:8000
cd frontend && npm run build               # max build 打包

# 移动端（base=/mobile/，API 仍走 /api/）
cd mobile && npm ci
cd mobile && npm run dev                   # http://localhost:5173/mobile/login
cd mobile && npm run check:enterprise      # lint + unit + build + bundle
cd mobile && npm run test:e2e              # Playwright 四视口
```

种子账号：`admin / ant.design`、`bob / ant.design`（V2 迁移写入）。

## 核心领域模型：钉钉式流程树（重要）

流程定义**不是** BPMN 图，也不是 nodes+edges 平面图，而是**单棵递归树**，参考实现见仓库内 `wflow-master/`（Vue 版 wflow：`src/views/admin/layout/process/{ProcessTree.vue,DefaultNodeProps.js}`、`src/views/common/process/config/ApprovalNodeConfig.vue`）。

- 存储：`t_process_definition.process`（JSONB，`ProcessDefinition.process` 字段）。**已弃用** `nodes`/`edges` 列。
- 节点类型：`ROOT / APPROVAL / CC / CONDITIONS / CONDITION / EMPTY / PARALLEL / DELAY / TRIGGER`（均已实现）。
- 结构：业务节点（ROOT/APPROVAL/CC/DELAY/TRIGGER）用**单个 `children`** 指向唯一后继（线性链，末端为 null）；`CONDITIONS`/`PARALLEL` 用 `branchs[]`（每个分支各带自己的 children 链）+ `children`（分支合流后的后续）；条件分支尾部用 `EMPTY` 占位。
- 并行网关 `PARALLEL`：每个分支按 `props.conditionMode`（`ALWAYS` 始终执行 / `WHEN_MATCHED` 条件命中）决定是否执行；分支内为单链 `APPROVAL/CC/EMPTY`，所有活跃分支落地任务并全部完成后汇聚到 `children`。
- 延时/触发器节点：`DELAY`/`TRIGGER` 会生成 `t_workflow_job` 持久化作业，由 `AutomationJobScheduler` 轮询，`ProcessEngine.completeAutomation` 在事务内推进阻塞节点。
- 审批人 `props.assignedType`：`ASSIGN_USER / ROLE / LEADER(第N级主管) / SELF / SELF_SELECT`。多人 `props.mode`：`AND`(会签) / `OR`(或签)。审批人为空 `props.nobody.handler`：`TO_PASS / TO_REFUSE`。
- 条件分支 `CONDITION.props`：`{ isDefault, groupsType(OR|AND), groups:[{ groupType(OR|AND), conditions:[{field, operator, value}] }] }`；`field` = 某表单字段的 `node.id`。
- 正式编号：`t_user.employee_no`（6 位数字，唯一非空）；`t_form_data.business_no`（12 位数字，草稿为空、提交后生成，唯一）。
- 任务类型 `t_task.task_type`：`APPROVAL` / `REWORK`。一级驳回生成申请人 `REWORK` 任务、表单状态 `NEEDS_REVISION`；修改后经 rework API 在原实例、原单号上重提。

后端引擎 `com.antflow.engine`：
- `ProcessEngine.start/approve/reject/withdraw` 沿树遍历（`engine.tree.ProcessTreeNav`），CC 非阻塞、`CONDITIONS`/`PARALLEL` 用 `engine.condition.ConditionEvaluator` 选分支、审批节点按 mode 决定推进（OR 首个通过即推进并跳过兄弟；AND 全部通过才推进）。
- `ProcessEngine.reject` 回退直接上一级；`forceReject(rejectToNodeId)` 支持驳回到指定非并行审批节点；一级驳回生成 `REWORK` 任务；`resubmitRework` 在原单重提。
- `engine.handler` 新增 `ParallelHandler`/`DelayHandler`/`TriggerHandler`；延时/触发器通过 `automation.WorkflowJobService` 持久化到 `t_workflow_job`，`AutomationJobScheduler` 轮询/恢复，`ProcessEngine.completeAutomation` 在事务内推进阻塞节点。
- `engine.resolver.AssigneeResolver` 解析审批人（含第 N 级主管：沿部门 `parentId` 上溯取 `leaderId`）。
- 乐观锁：`t_process_instance.version` / `t_task.version`（MyBatis-Plus `OptimisticLockerInnerInterceptor`）。

前端设计器 `frontend/src/pages/designer/process/`：
- 递归树渲染 `ProcessTree.tsx` + `NodeChain.tsx` + `nodes/*`（节点卡片）；状态在 `useProcessDesignerStore.ts`（zustand，含 insert/remove/addBranch/updateProps）。
- 节点配置面板在 `config/*`；`ProcessDesigner.tsx` 用 antd Drawer 承载。**已移除 `@xyflow/react`**。

## 权限、审计与自动化（V20/V21）

- 操作级 RBAC：`t_permission`（`kind = ACTION | PAGE`）、`t_role_permission`、`t_role_department`；`authz.AuthorizationService` / `RoleAdminService` / `SecurityAuthorizationController`（`/api/security/*`）。
- 表单资源授权：`t_form_resource_grant`（USER/ROLE 授权）；`t_process_instance.started_dept_id` 保存发起部门快照。
- 审计：`t_audit_event` 追加只读（trigger 保护），`AuditController` 提供 `/api/audit/events`、`/api/audit/export`、`/api/audit/archives/{id}/download`；`t_audit_archive` 支持归档校验与下载。
- 自动化：`t_workflow_job`（DELAY/TRIGGER）+ `AutomationJobScheduler` 轮询/恢复 + `WebhookClient`（SSRF 防护：allowed hosts、https-only、禁私网地址可配）。

## 约定与坑

- **表单数据以 `node.id`（nanoid）为键**：`t_form_data.data = { "<字段node.id>": 值 }`（见 `FormRenderer.tsx`）。条件分支的 `field` 即字段 node.id。
- 表单/流程 1:1：`t_process_definition.form_def_id` UNIQUE。发起流程时后端自动建 `t_form_data(SUBMITTED)` + `t_process_instance(RUNNING)`。
- 发起接口 `POST /api/instances/start` body：`{ formCode, data, selfSelected }`，`selfSelected: { [nodeId]: number[] }` 提供所有 SELF_SELECT 节点的自选审批人。
- **前端存在历史 tsc 错误**（app.tsx / requestErrorConfig.ts / 部分 form-fields / login 等，与流程改造无关）；`npm run build`（max build）不因类型错误失败，但 `npm run lint`/`tsc` 在这些历史文件上仍报错。这是独立的待整改项，勿误判为本次改动引入。

## 二期（未做）

连续多级主管(LEADER_TOP)、依次会签(NEXT)、任务级超时处理、节点级表单字段权限、转交/加签、流程发布版本快照、列表分页、实例详情读权限收敛。

## 企业级移动端

- 代码：`mobile/`（与桌面端独立依赖/构建，同域部署 `/mobile/`）。
- 路由：工作台/待办/我的壳层 + 表单填写/草稿/自选/确认、任务详情、流程详情、账号安全。
- 品牌：`BrandProvider` + 公开品牌 DTO；失败 fallback；不接受任意服务端 CSS。
- 企业微信：**仅** `PlatformAdapter` 边界，一期不实现免登/JS-SDK/应用消息。
- 验收：`docs/mobile-enterprise-verification.md`；代理速查：`codex.md`。
