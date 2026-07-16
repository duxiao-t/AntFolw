# CLAUDE.md

本文件为 Claude Code 在此仓库工作提供指引。

## 这是什么

**AntFlow** —— 一个流程审批后台管理系统（对标钉钉：可视化表单配置 + 审批流配置）。单仓多模块 monorepo：

```
ant-flow/
├── backend/     # Spring Boot 3 + Java 17 + MyBatis-Plus + Flyway + PostgreSQL；自研轻量审批引擎
├── frontend/    # Umi Max 4 + React 18 + antd 6 + zustand（ant-design-pro 底座）；有自己的 CLAUDE.md
├── infra/       # docker-compose（postgres:17）+ initdb 扩展脚本
└── docs/        # superpowers/specs（设计规格）、superpowers/plans（实施计划）
```

> 注意：`backend/` 与 `frontend/` 各有独立依赖与构建。**先 `cd` 进对应模块再执行命令**，不要在仓库根跑 `mvn`/`npm`。前端另有 `frontend/CLAUDE.md`（Biome-only、TS strict、`/antd` 与 `/pro-upgrade` skill），改前端前先读它。

## 运行 / 构建

```bash
# 数据库
cd infra && docker compose up -d          # postgres:17，含 ltree/pgcrypto 扩展

# 后端（首次启动自动跑 Flyway V1..V4）
cd backend && mvn -B spring-boot:run       # http://localhost:8080
cd backend && mvn test                     # 单元测试（不需 PG）

# 前端（dev 代理 /api → :8080）
cd frontend && npm install
cd frontend && npm start                   # http://localhost:8000
cd frontend && npm run build               # utoopack 打包
```

种子账号：`admin / ant.design`、`bob / ant.design`（V2 迁移写入）。

## 核心领域模型：钉钉式流程树（重要）

流程定义**不是** BPMN 图，也不是 nodes+edges 平面图，而是**单棵递归树**，参考实现见 `D:\code\wflow-master`（Vue 版 wflow：`src/views/admin/layout/process/{ProcessTree.vue,DefaultNodeProps.js}`、`src/views/common/process/config/ApprovalNodeConfig.vue`）。

- 存储：`t_process_definition.process`（JSONB，`ProcessDefinition.process` 字段）。**已弃用** `nodes`/`edges` 列。
- 节点类型：`ROOT / APPROVAL / CC / CONDITIONS / CONDITION / EMPTY`（首期核心子集；并行 `CONCURRENTS`、延时、触发器等为二期）。
- 结构：业务节点（ROOT/APPROVAL/CC）用**单个 `children`** 指向唯一后继（线性链，末端为 null）；`CONDITIONS` 用 `branchs[]`（每个 `CONDITION` 分支各带自己的 children 链）+ `children`（分支合流后的后续）；分支尾部用 `EMPTY` 占位。
- 审批人 `props.assignedType`：`ASSIGN_USER / ROLE / LEADER(第N级主管) / SELF / SELF_SELECT`。多人 `props.mode`：`AND`(会签) / `OR`(或签)。审批人为空 `props.nobody.handler`：`TO_PASS / TO_REFUSE`。
- 条件分支 `CONDITION.props`：`{ isDefault, groupsType(OR|AND), groups:[{ groupType(OR|AND), conditions:[{field, operator, value}] }] }`；`field` = 某表单字段的 `node.id`。

后端引擎 `com.antflow.engine`：
- `ProcessEngine.start/approve/reject/withdraw` 沿树遍历（`engine.tree.ProcessTreeNav`），CC 非阻塞、`CONDITIONS` 用 `engine.condition.ConditionEvaluator` 选分支、审批节点按 mode 决定推进（OR 首个通过即推进并跳过兄弟；AND 全部通过才推进）。
- `engine.resolver.AssigneeResolver` 解析审批人（含第 N 级主管：沿部门 `parentId` 上溯取 `leaderId`）。
- 乐观锁：`t_process_instance.version` / `t_task.version`（MyBatis-Plus `OptimisticLockerInnerInterceptor`）。

前端设计器 `frontend/src/pages/designer/process/`：
- 递归树渲染 `ProcessTree.tsx` + `NodeChain.tsx` + `nodes/*`（节点卡片）；状态在 `useProcessDesignerStore.ts`（zustand，含 insert/remove/addBranch/updateProps）。
- 节点配置面板在 `config/*`；`ProcessDesigner.tsx` 用 antd Drawer 承载。**已移除 `@xyflow/react`**。

## 约定与坑

- **表单数据以 `node.id`（nanoid）为键**：`t_form_data.data = { "<字段node.id>": 值 }`（见 `FormRenderer.tsx`）。条件分支的 `field` 即字段 node.id。
- 表单/流程 1:1：`t_process_definition.form_def_id` UNIQUE。发起流程时后端自动建 `t_form_data(SUBMITTED)` + `t_process_instance(RUNNING)`。
- 发起接口 `POST /api/instances/start` body：`{ formCode, data, selfSelected }`，`selfSelected: { [nodeId]: number[] }` 提供所有 SELF_SELECT 节点的自选审批人。
- **前端存在历史 tsc 错误**（app.tsx / requestErrorConfig.ts / 部分 form-fields / login 等，与流程改造无关）；`npm run build`（utoopack）不因类型错误失败，但 `npm run lint`/`tsc` 在这些历史文件上仍报错。这是独立的待整改项，勿误判为本次改动引入。

## 二期（未做）

并行分支、延时/触发器节点、连续多级主管(LEADER_TOP)、依次会签(NEXT)、超时处理、驳回到指定节点、节点级表单字段权限、转交/加签、流程发布版本快照、列表分页、实例详情读权限收敛。
