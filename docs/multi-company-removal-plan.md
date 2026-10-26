# 多公司能力拆除方案（收敛为单公司模式）

> 状态：进行中。第 0 阶段（数据收敛）已落地；代码拆除按下面的阶段推进。

## 1. 目标与口径

把系统从"多公司隔离"收敛为"单一公司"，且**只保留一家公司**。

三条已确认的口径：

1. **归属目标 = 最早创建的公司**（`created_at, id` 升序取第一条），**不写死 id 或名称**——
   同一套代码部署在多台服务器，各环境公司名不同。
2. **只删代码、保留列**：`t_company` 与 8 张表的 `company_id` 保留，供回滚与后续清理，
   应用层不再读写。
3. **四个范围全删**：前端入口与归属选择、授权与数据隔离逻辑、企微多公司同步、表单授权的公司维度。

## 2. 现状盘点

| 维度 | 规模 |
|---|---|
| 后端 | 72 个文件、670 处命中 |
| 前端 | 约 40 个源文件（另有 `.umi*` 与 `services/antflow/*` 属生成产物，随代码重建） |
| 数据库 | `t_company` + 8 张表带 `company_id`；`t_form_data`/`t_process_instance` 另有 `company_inferred` |
| 数据 | 28 家公司，330 条单据（283 归 AntFlow、32 条无归属、少量散落 22–26） |

### 关键耦合（决定了拆除顺序）

- `Company` 实体只被自身 3 个文件引用 → 可独立删除。
- `CompanyAttribution` 被 11 个文件引用，是"新数据归属哪家公司"的唯一入口。
- `OrgCompanyAccess` 被 6 个文件引用（部门/用户/公司控制器）。
- 授权层用 **SQL 谓词**（不是上面这些类）实现隔离：
  `CompanySnapshotSql`（`company_id IS NOT NULL AND company_inferred = false`）、
  `InstanceScopeSql`、`DataScopeResolver`（`company_id = ?` + `inCompanyDataScope`）。
- 企微 16 个文件用 `Long companyId` 原始值传参，与 `t_wecom_*` 的 `company_id` 绑定。

**关键约束**：授权谓词要求 `company_id` 非空。一旦归属代码停止写入 `company_id`，
新数据会被旧谓词**静默隐藏**。所以**授权层必须先于归属层拆除**。

## 3. 分阶段拆除

### 第 0 阶段：数据收敛（已完成）

`V73__collapse_to_single_company.sql`：全部部门/实例/单据/企微映射改挂到最早的公司，
清空 `company_inferred`，其余公司逻辑删除。企微映射先按"保留最小 id"去重以避开唯一键。
`t_form_data_correction.company_id_at_time` 是历史快照，**刻意不改写**（改写等于伪造审计）。

验收：`t_company` 只剩 1 条 `deleted = 0`；所有业务行 `company_id` 一致且非空。

### 第 1 阶段：授权层去公司维度（安全边界，风险最高）

| 文件 | 动作 |
|---|---|
| `authz/CompanySnapshotSql.java` | 删除；`ADMIN` 片段若仍需，迁到调用处 |
| `authz/InstanceScopeSql.java` | 去掉 `company_id = ? AND company_inferred = false` 谓词与 company 参数 |
| `authz/DataScopeResolver.java` | 删除 `companyOf`、`inCompanyDataScope` 的公司判定，只留 owner/department 维度 |
| `authz/FormAccessGuard.java`、`InstanceAccessGuard.java` | 去掉 `requireSameCompany` 一类判定 |
| `authz/FormGrant*`（5 个） | 移除授权的公司维度（候选查询、存储、支撑类） |
| `authz/AuthorizationService.java`、`DataPermissionPolicyHandler.java` | 去掉对外暴露的公司门槛方法 |

验收：同一角色在数据范围内看到的数据与改动前一致（单公司下本应全可见）；
`AuthorizationServiceContractTest` 的冻结清单同步更新。

### 第 2 阶段：组织层去公司域

| 文件 | 动作 |
|---|---|
| `org/Company.java`、`CompanyController.java`、`CompanyMapper.java` | 删除（对应 `GET/POST/PUT /api/companies` 下线） |
| `org/OrgCompanyAccess.java` | 删除 |
| `org/CompanyAttribution.java` | 删除 |
| `org/Department*.java` | 去掉 `companyId` 字段与校验 |
| `org/User*Service.java`、`UserGuards.java`、`PickerController.java` | 去掉按公司过滤 |

验收：部门/用户接口不再接收或返回 `companyId`；通讯录、选择器行为不变。

### 第 3 阶段：业务层去归属

`engine/dto/StartCmd.java`、`engine/ProcessStarter.java`、`engine/resolver/DirectManagerStrategy.java`、
`form/runtime/*`（含 `MobileDraft*`）、`form/importer/*`（5 个）、`form/correction/*`（2 个）、
`mobile/workflow/*`（5 个）、`task/*`（3 个）：
移除 `companyId` 入参、归属解析调用与 `company_inferred` 写入。

验收：发起流程 / 无流程提交 / 草稿 / 草稿转正式 / 导入 / 修正 六条写路径均不再触碰公司。

### 第 4 阶段：企微去公司

`integration/wecom/*`（16 个）：配置、部门/用户映射、同步任务、登录与消息分发不再按公司分区；
`WecomAccessGuard.requireCompany/requireCompanyAccess` 删除。

验收：企微配置读写、通讯录同步、消息推送按单实例工作。

### 第 5 阶段：前端

- 删除：`pages/settings/Company.tsx` + 路由 + 菜单项、`pages/admin/FormData/useCompanyAttribution.ts`、
  `services/antflow/companyController.ts`。
- 清理：组织通讯录、部门管理、表单授权、导入向导、企微设置里的公司选择/筛选/列（约 30 个文件）。
- 契约：`antflow-api.json` 与 `shared/api.generated.d.ts` 重新生成。

验收：全站无公司切换/选择/筛选入口；组织与表单流程操作不变。

## 4. 风险与回滚

| 风险 | 说明 | 处置 |
|---|---|---|
| 数据越权 | 删谓词时若误删 owner/部门维度，会放大可见范围 | 每阶段跑 `AuthorizationServiceContractTest` 与集成用例；改前后对比同角色可见集合 |
| 静默隐藏 | 归属层先删会导致新数据 `company_id` 为空、被旧谓词过滤 | 严格按"先授权、后归属"的顺序 |
| 唯一键冲突 | 企微映射归并撞 `(company_id, x_id)` | 迁移内先按最小 id 去重 |
| 不可逆 | 逻辑删除的公司可恢复，但已合并的归属信息无法还原到原公司 | 执行前 `pg_dump` 备份 |

## 5. 回退后的预期行为

1. 系统内只有一家公司，任何界面都不再出现公司选择、切换、筛选或"所属公司"字段。
2. 权限只在"本人 / 本部门 / 全部"三个维度上判定，不再有"同公司"这一层。
3. 新产生的单据、实例、草稿不再写入公司归属；读取侧也不依赖它。
4. 企微只保留一套配置与一份通讯录映射，不再按公司分区。
5. 数据库仍保留 `company_id` 等列（值为历史快照），但没有任何代码读写它们。
