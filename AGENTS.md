# AGENTS.md — AntFlow 仓库 Agent 协作说明

> 本文件是仓库**唯一的 agent 协作入口**，整合自原 `agent.md`、`codex.md` 与旧版 `AGENTS.md`（26 行），
> 并从 `ant-design-pro-master/CLAUDE.md` 提取适用于本仓库的条目（2026-10-17）。读者仅凭本文件即可掌握
> 四份源文件的全部要点与约束；合并时的取舍与依据见
> [docs/agent-merge-record-2026-10-17.md](docs/agent-merge-record-2026-10-17.md)。

## 0. 命名与兼容性

- 本文件名 `AGENTS.md` 是多数 agent 工具（Codex、Cursor、Copilot 等）默认自动发现的仓库级约定名，无需额外配置。
- 本文件是仓库**唯一的 agent 协作入口**；项目领域事实的权威文件仍是根 `CLAUDE.md`（流程树模型、引擎、
  权限审计细节）与 `frontend/CLAUDE.md`，本文件与其不重复。
- 改前端或流程引擎前，仍应先读上述两份 `CLAUDE.md`。
- 四份原文件的合并取舍、命名变更与原文回溯方式见
  [`docs/agent-merge-record-2026-10-17.md`](docs/agent-merge-record-2026-10-17.md)（一次性档案，不随仓库演进更新）。

## 1. 项目速览

**AntFlow** —— 企业级表单与审批平台（对标钉钉：可视化表单设计 + 审批流编排 + 审批运行时 + 权限/审计/自动化），
单仓多模块，含桌面管理端与移动审批端。

| 模块 | 职责 |
| --- | --- |
| `backend/` | 审批引擎、RBAC / 审计 / 自动化、Flyway 迁移与 REST API |
| `frontend/` | 桌面管理端：表单/流程设计、组织、任务、权限安全、报表、系统设置 |
| `mobile/` | 移动审批端，部署 base `/mobile/`，企业级审批闭环 |
| `infra/` | Nginx 配置示例 |
| `docs/` | 设计规格与计划、企业级验收记录、会话交接总结 |
| `shared/` | 两端共享契约（`api.generated.d.ts`、`form-validation.ts`） |

技术栈、依赖与版本要求见 `README.md` §一、§四。

## 2. 开工前：优先读取

1. 读 `docs/PROJECT.md`（系统地图、关键流程、不变量）与 `docs/HANDOFF.md`（当前交接状态）。
2. 检查 Git 状态、分支、HEAD 与近期提交。
3. 只查阅 `docs/DECISIONS.md` 中**与本任务相关**的决策条目，不要通读。

## 3. 项目不变量与硬约束

- Flyway **已执行的迁移只前滚，不修改历史文件**；新变更一律新增版本。当前迁移至 **V58**（实测 `backend/src/main/resources/db/migration/`）。
- 后端端点能力、资源授权和数据范围**共同构成安全边界**；**前端与菜单不是安全边界**。
- 受控表的通用查询默认被数据权限拦截；自助查询必须使用专用语句并显式豁免（`@InterceptorIgnore(dataPermission = "true")`）。
- 能力码**只增不改**；废弃码由目录同步器标记，不复用旧码表达新语义。
- `/api/mobile/users|departments` 是移动端已登录用户的产品能力；桌面选择器使用 `/api/pickers/**`。
- 鉴权分三层：**端点能力 → 资源授权 → 数据范围**。
- 表单使用范围只控制手机目录、打开、草稿与发起；模板维护要求 `t_form_maintainer` 成员关系与对应原子能力同时满足；
  业务数据与实例读取只看自身能力与数据范围。
- **不提交任何密钥**；生产配置没有数据库口令、JWT、MinIO 或集成密钥默认值，种子账号只在 `local` profile 有默认值。

## 4. 开发期命令

> 依赖与版本要求见 `README.md` §四；本地启动、部署、隔离测试栈与压测见 `README.md` §5、§十一。
> 命令形态：在仓库根执行用 `npm --prefix <dir> run …`；在模块内执行用 `cd <dir> && …`。以下命令块均注明所在目录。

### 4.1 后端（`backend/`）

```bash
cd backend && mvn -B test                # 单元测试（不需要 PostgreSQL）
cd backend && bash build.sh package      # 宿主机无 JDK/Maven 时用容器编译
```

**非 local 环境必须通过 `ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD` 一次性初始化管理员，不存在公开默认密码。**

### 4.2 移动端（`mobile/`）

```bash
cd mobile && npm run check:enterprise     # lint + unit + build + bundle 预算
cd mobile && npm run test:e2e             # Playwright 四视口；E2E 端口优先 5174（E2E_PORT）
cd mobile && npm run lint && npm test && npm run build   # 任一改动的最低门槛
cd mobile && npm run test:e2e -- key-pages.visual.spec.ts  # 视觉基线刷新（四视口同步）
```

### 4.3 桌面端（`frontend/`）

```bash
cd frontend && npm run lint && npm test && npm run build   # lint = biome:lint && tsc
cd frontend && npx antd lint ./src
```

**在 WorkBuddy / CodeBuddy 桌面环境里跑类型检查，用 `npm run tsc:fast`（即 `tsc --noEmit`），不要用 `npm run tsc`。**
原因：`npm run tsc` 会先执行 `max setup`，它用 `rimraf` 删 `.umi`；本机的 safe-delete 守卫对
"一次删除超过 50 个文件"会拦截，导致 `.umi` 被删了一半、随后 `tsc` 报出大量与改动无关的假错误。
`tsc:fast` 跳过重建，前提是 `.umi` 已存在（`npm run dev`/`build` 过）。

若确实需要重建 `.umi`（例如首次拉代码或改了路由约定），用：

```bash
cd frontend && CODEBUDDY_SAFE_DELETE_ENABLED=0 npm run tsc
```

CI（GitHub Actions）没有这个守卫，`npm run lint` 可正常使用。

## 5. 后端能力与数据模型

- **审批引擎**：树形流程（**非 BPMN、非 nodes+edges 平面图**）+ `PARALLEL` 并行网关 + `DELAY`/`TRIGGER`
  自动化节点 + `REWORK` 原单重提；引擎在 `com.antflow.engine`。
- **流程定义存储**：`t_process_definition.process`（JSONB）；已弃用 `nodes` / `edges` 列。
- **权限审计**：操作/页面权限（`t_permission`）、角色授权（`t_role_permission`）、表单使用范围
  （`t_form_resource_grant`）、模板维护成员（`t_form_maintainer`）、追加只读审计（`t_audit_event`）
  与归档（`t_audit_archive`）。
- **节点级字段权限**：审批节点 `formPerms` 三态（隐藏 / 只读 / 可编辑），可编辑字段在同意时回写表单数据。
- **自动化**：`t_workflow_job` + `AutomationJobScheduler` + `WebhookClient`（SSRF 防护：allowed hosts、
  https-only、禁私网地址可配）。
- **正式编号**：`t_user.employee_no`（6 位数字、唯一非空）；`t_form_data.business_no`（12 位数字，正式提交后生成）。
- **桌面端对应页面**：`frontend/src/pages/{security,report,settings}/*`。

## 6. 移动端

### 6.1 架构要点

- **部署**：同域 `/mobile/` 静态资源 + `/api/` 后端；生产把 `mobile/dist/` 挂到站点 `mobile/` 子目录。
- **壳层**：底部三栏「工作台 / 待办 / 我的」；详情页与表单页无底栏。
- **路由**（basename `/mobile`）：`/login`、`/workbench`、`/tasks`、`/profile`、`/apps`、`/apps/favorites`、
  `/forms/drafts`、`/forms/:code`、`/forms/:code/self-select`、`/forms/:code/confirm`、
  `/forms/:code/success/:instanceId`、`/tasks/:taskId`、`/processes/:instanceId`、`/profile/security`。
- **状态**：TanStack Query + feature store；access token 内存态，刷新走统一 HTTP 包装。
- **平台**：`PlatformAdapter`（Browser 实现；企业微信二期）。**页面禁止**直接判断企业微信环境。
- **安全**：实例 / 任务 / 附件均需归属校验；上传白名单类型与大小；发起与审批支持 `Idempotency-Key`。
- **业务规则**：审批详情显示工号不显示任务号，表单详情显示单号不显示实例 ID；驳回回到上一级，
  第一级驳回生成申请人 `REWORK` 任务（原表单、附件、实例、单号保留，可在原单重提）；
  移动端 API：`GET/PUT /api/mobile/rework-tasks/{id}`、`POST /api/mobile/rework-tasks/{id}/resubmit`。

### 6.2 品牌配置

- 移动端只消费**已发布**品牌 DTO → CSS variables；`BrandProvider` 拉 `/api/public/branding`，
  非合法色或失败时用内置 fallback，**不接受任意 CSS**。
- 桌面端品牌编辑/发布能力以对应分支提交为准；客户端始终可 fallback。默认主色企业级对比：`#0b57d0`。

### 6.3 设计稿对齐（2026-07-31 已完成，保留为设计基线）

设计稿源：`_preview/`（20 个页面 + 总目录 `index.html`，已入库、可直接浏览器打开）与 `docs/superpowers` 设计文档。
风格方向「A. 清晰秩序」（Ant Design 企业蓝 + 中性背景）。**后续开发以「已实现页面」与最新验收记录为准。**

**视觉令牌**

| 项 | 值 |
| --- | --- |
| 主色 / 状态色 | 主色 `#1677ff`；成功 `#31a354`、警告 `#fa8c16`、危险 `#ff4d4f` |
| 背景与文字 | 背景 `#f5f6f8`、卡片 `#ffffff`、分隔线 `#edf0f2`、正文 `#202830`、副文 `#8b949e` |
| 圆角 | surface 8–10px、control 7px、sheet 顶部 15px |
| 字体 | 标题 18–20px / 600–700；正文 11–13px；meta 9–10px；首选 `PingFang SC` / `Microsoft YaHei` |
| 布局 | 底部导航固定 55–57px，三 Tab 等宽，激活色用主色；卡片间距 8px、组间距 12px、行内 7–11px |
| 状态 tag | 进行中=蓝底蓝字、已通过=绿底绿字、已驳回=红底红字、已完成=灰底灰字 |
| 时间线 | 左边线 1px `#d9dde2`；节点圆点 7px（已完成主色 / 进行中白底橙边 / 未到达浅灰） |

**关键页面骨架**（编号 01–13、15–21，缺 14）

| 页面 | 布局要点 |
| --- | --- |
| 01 登录 | 蓝底圆角「A」方块 54×54；标题 28–30px/800；副标题「移动审批，让每一次流转清晰可见」；input 单边框透明背景**不放白卡**；全宽主色按钮；底部 12px 灰字「企业微信免登录（二期）」 |
| 02 工作台 | 顶部白底横条（工作台 / AntFlow 科技 / 头像 29px）→ 蓝色品牌卡（问候 + `N 项待办` 徽章，徽章白底主色字）→ 常用应用 4×2 宫格（图标 33–38px，奇蓝偶绿交替，第 8 项 `＋ 更多`）→ 最近流程卡（圆点 + 主标 + meta + 状态 tag） |
| 03 全部应用 | `‹ 全部应用` + 右上「管理」；搜索框；分类 chip（全部/人事/财务/行政）；多分组宫格白卡（最近使用/人事申请/行政申请） |
| 04 管理常用应用 | `‹ 管理常用应用` + 「完成」；「已添加」排序（首期可用上下箭头替代拖拽）+「可添加」行（含 `+ 添加` tag） |
| 05 动态表单 | `‹ 请假申请` + 右上「草稿」；字段分组白卡 +「附件」dashed 上传区；底部固定「下一步」 |
| 06 自选审批人 | `‹ 选择审批人` + 「确定」；搜索框；「直属主管 / 单选」+「最近选择」人员行（头像 + 姓名 + 部门 + radio） |
| 07 提交确认 | `‹ 确认提交`；应用图标卡 +「申请摘要 / 修改」KV +「审批流程」时间线；底部固定「确认提交」 |
| 08 提交成功 | 居中绿色 ✓ 圆 56px →「提交成功」→ 双行说明 → 并排「返回工作台」（描边）+「查看进度」（实心） |
| 09 / 13 / 15 任务中心 | 标题「待办」+ 角标；Tab 顺序 **待我处理 / 我发起的 / 已处理**；搜索 + 筛选 chip；任务卡（meta + 标题 + 摘要 + 人员行 + 状态 tag）；13 显示当前节点、15 显示「你的处理意见」 |
| 10 审批详情 | `‹ 审批详情` + `•••`；顶部摘要白卡（图标 + 标题 + meta + 状态条）+ 申请内容 KV + 审批进度时间线 + 附件卡；底部三按钮 **更多 / 驳回（红边白底）/ 同意（主色实心）** |
| 11 / 12 / 20 / 21 同意与驳回弹层 | 蒙层 `rgba(0,0,0,.38)` + 15px 圆角底部 sheet；h3 居中；textarea（驳回含「驳回到」下拉）；**并排**「取消 / 确认同意（驳回）」 |
| 14 流程进度 | `‹ 流程进度` + `•••`；顶部摘要（图标 + 标题 + 实例编号 + 状态条）+ 完整时间线 + 申请摘要 KV；底部**全宽红底白字**「撤回流程」 |
| 16 我的 | 个人卡（48px 头像 + 姓名 + `部门 · 角色` + chevron）+ 统计三栏（待处理/进行中/本月完成，主色数字）+ 菜单组 1（常用应用管理 / 草稿箱 / 账号与安全）+ 菜单组 2（帮助与反馈 / 关于 AntFlow）+ 红色居中「退出登录」 |
| 17 草稿箱 | `‹ 草稿箱`；标题 + 自然语言时间（`保存于今天 11:36`）+ `已填写 N/M` + 右下「删除」与「继续填写」 |
| 18 账号与安全 | 行式白底列表（登录账号 / 修改密码 / 登录设备 / 企业微信绑定（二期）/ 隐私与数据）+ 底部红边「退出当前账号」 |
| 19 离线/失败 | **整页空状态**（图标 +「网络暂时不可用」+「已保留未提交的表单内容，网络恢复后可继续操作」+「重新加载」），替换顶部横幅，保留恢复后自动重试 |

**动画与微交互**：卡片/按钮/状态 tag 用 120–200ms ease 过渡，避免长动画；时间线节点首次渲染 fade-in +
上移 8px，按序号 stagger 50ms；弹层 sheet `translateY(100%) → 0`、250ms ease-out；品牌卡 hover lift 仅限 web 调试。

**共享结构调整**：`AppPage` 需支持「无底栏 + 沉浸式」版式（审批详情、流程进度、提交成功、登录）；
`tokens.css` 增加轻量阴影与状态色 token；任务中心 Tab 顺序变更会影响 task-card 截图基线。

### 6.4 移动端工程约束

- 技术栈限定：Vite + React 19 + antd-mobile v5 + TanStack Query + Zustand；**不要引入 antd-mobile 之外的 UI 库**。
- **不修改** `shared/api`、`shared/platform`、`shared/recovery`、`shared/telemetry`。
- **不修改** `auth.store`、`recoveryDraft.store`、`submitFlow.store`、`apps.store` 等核心 store 接口（仅允许新增派生 selector）。
- 所有改动必须通过 `npm run lint && npm test && npm run build`。
- 视觉基线刷新命令见 §4.2，需同步刷新四个视口（android-360 / iphone-375 / iphone-390 / iphone-430）；
  **改动设计而不是放宽基线**。

## 7. 桌面端（frontend）工程约定

- `frontend/` 以 ant-design-pro 为底座，细则见 `frontend/CLAUDE.md`；以下为必须遵守的硬规则。
- 仓库内 `ant-design-pro-master/` 是**上游脚手架副本**（已 .gitignore、不参与构建），其中的 `utoopack` 构建、
  `/pro-upgrade` 与 `/antd` skill、Cloudflare Worker、mock 体系、8 语言 i18n 等只对脚手架本身有效，与本项目无关。
- **只使用 Biome**——不用 ESLint、不用 Prettier；`npm run biome:lint` 与 `npx antd lint ./src` 都必须通过。
- **写 antd 代码前先 `npx antd info <Component>`** 查 API，不要凭记忆猜。
- `npm run simple` **不可逆**，执行前必须先提交或建分支。
- **禁止手改生成目录**：`frontend/src/services/ant-design-pro/`（由 `npm run openapi` 生成）。
- **约定式文件优先**：`config/config.ts`（配置）、`config/routes.ts`（路由）、`src/app.tsx`（运行时）、
  `src/access.ts`（权限）、`src/requestErrorConfig.ts`（请求配置）。
- 状态管理：`useModel('filename')`（`src/models/` 全局 hooks）、`useModel('@@initialState')`
  （currentUser/settings）、ProTable `request` 为主、复杂服务端状态用 `@tanstack/react-query`。
- 样式优先级：Tailwind CSS v4（布局）→ antd-style v4 `createStyles`（主题 token）→ CSS Modules → Less（仅遗留）。
- 页面就近放置：每个页面目录含 `index.tsx`、可选 `service.ts`、`_mock.ts`、`data.d.ts` 与样式文件。
- TypeScript strict；Node ≥ 22；使用 `package-lock.json`（不用 yarn/pnpm）。
- `src/.umi` 为自动生成目录；dev server 异常时删除并重启。

## 8. 通用编码行为准则

1. **先想后写**：显式陈述假设；有多种解释时先列出来，不要默默选一个；有更简单方案要说；不清楚就停下来问。
2. **最简优先**：只做被要求的事；不为一次性代码造抽象；不加没被要求的"可配置性"；不为不可能发生的场景写错误处理。
   写完 200 行发现 50 行能解决，就重写。
3. **外科手术式改动**：不动无关代码、注释与格式；不重构没坏的东西；匹配既有风格；发现无关死代码只**提出来**，不擅自删。
   自己改动造成的孤儿引用（import/变量/函数）要清理；别人留下的不清。判定标准：**每一行改动都能追溯到需求**。
4. **目标驱动执行**：把任务转化为可验证目标（"加校验" → "先写非法输入的测试再让它通过"）；多步任务先给出
   `步骤 → 验证方式` 的简短计划，验证强则能独立循环。

## 9. Git 与提交约定

- 格式：**`类型(范围): 描述`**（中文），示例：`功能(移动端): …`、`修复(移动端): …`、`测试(移动端): …`、
  `文档: 更新企业级移动端运行与验收说明`。
- 历史提交作者：`AntFlow Bot <bot@antflow.local>`。
- 一次提交只做一件事；不把格式化、重构、功能混在一起。

**类拆分额外纪律**（方案见 `docs/class-decomposition-execution-2026-10-20.md`）：

- **一次提交只搬一个方法簇**，不把两个簇、或"搬迁 + 顺手修 bug"混在一个提交里——否则无法用
  "测试仍绿且行为不变"来验证，回滚也会连带撤掉别的改动。
- 提交信息带**行数/方法数前后对比**，例如：`重构(表单): 抽出 Schema 校验器（1403/76 → 812/31）`。
- 拆分 PR **不改** `docs/health-metrics.baseline.json`：拆小只会让 H7 数字下降，门禁只拦变大；
  基线由集成者在批次合并后统一 `--write` 收紧，避免多个 PR 争抢同一份 JSON。
- 拆分期间 `frontend/config/antflow-api.json` 必须**逐字不变**——它变了说明动了接口，"只重构"的验证就失效了。

## 10. 文档约定

- **代码、配置、schema 和测试优先于文档**；文档与实现对不上时，以代码为准并修正文档。
- 持久架构事实变化时更新 `docs/PROJECT.md`；重要决策写入 `docs/DECISIONS.md`。
- 不记录秘密或生产数据。

## 11. 验收与自检

移动端相关改动完成后自检：

1. 是否逐条对照 `_preview/` 中的页面预览？
2. 是否覆盖每个页面的标题层级、配色、圆角、间距、按钮位置、状态 tag 颜色？
3. 是否**保留了全部现有功能**（草稿、提交流程、审批操作、撤回），而不只是改了 UI？
4. 是否更新了视觉回归基线，让快照反映新设计，而不是反过来放宽设计？

验收文档：`docs/mobile-enterprise-verification.md`。

## 12. 开发侧非目标与待验证

**移动端开发侧非目标**：企业微信免登 / JS-SDK / 应用消息**仅保留 `PlatformAdapter` 边界**，页面里不得直接判断运行环境；
不引入 antd-mobile 之外的 UI 库；不实现完整主题编辑器与报表看板。

**待验证 / 未完成**：干净 PostgreSQL 17 上完整执行全部迁移；真实后端二级及更高层驳回、
AND / 多审批人与复杂条件分支、附件删除 / 新增 / 重提一致性。

> 产品对外边界（单租户、OIDC、企业微信入口数量与验收依赖、HTTPS 依赖、移动端不提供设计器与组织管理、
> 节假日工作日历、PWA）见 `README.md` §十二。
