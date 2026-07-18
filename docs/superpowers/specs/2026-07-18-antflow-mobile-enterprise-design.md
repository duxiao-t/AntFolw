# AntFlow 企业级移动端设计规格

> 状态：已确认  
> 日期：2026-07-18  
> 适用项目：`D:\code\ant-flow`  
> 交付形态：独立移动 H5/PWA，React + Ant Design Mobile

## 1. 背景

AntFlow 当前已经具备桌面管理端、动态表单设计器、递归流程树、审批引擎、任务中心和组织数据。移动端需要服务普通员工与审批人，覆盖“发现应用 → 填写申请 → 发起流程 → 处理待办 → 查看结果”的完整闭环。

移动端不是桌面后台的缩小版。表单设计、流程设计、组织维护、用户角色、报表和系统管理继续留在桌面端。移动端强调高频办事、单手操作、状态可读和弱网恢复。

## 2. 已确认决策

| 主题 | 决策 |
|---|---|
| 主要用户 | 普通员工与审批人 |
| 导航 | 工作台 / 待办 / 我的，三个底部菜单 |
| 通讯录 | 首期不展示独立通讯录 |
| 工作台 | 应用入口型：常用应用 + 最近流程 |
| 工程形态 | 仓库新增独立 `mobile/` 工程 |
| UI 技术 | React 19、TypeScript、Ant Design Mobile |
| 视觉方向 | A“清晰秩序”：企业蓝、中性色、轻量层级、多色语义状态 |
| 企业微信 | 首期预留接口，二期接入免登和应用消息 |
| 品牌能力 | 桌面后台支持增强品牌配置与实时预览 |
| 部署 | 桌面端、移动端和 API 独立构建，同域部署 |

## 3. 目标与非目标

### 3.1 首期目标

1. 普通员工可以登录、查看已发布审批应用、填写动态表单并发起流程。
2. 发起人可以保存草稿、查看流程进度，并在规则允许时撤回。
3. 审批人可以查看待办、审批详情、同意、驳回和查看已处理记录。
4. 管理员可以在桌面后台配置品牌，并在发布前预览移动端效果。
5. 移动端具备企业级权限、幂等、错误恢复、审计、性能和测试门禁。
6. 二期接入企业微信时不重构业务页面和核心状态层。

### 3.2 首期非目标

- 手机端表单设计和流程设计。
- 手机端组织、用户、角色和权限管理。
- 独立通讯录、聊天或会话消息。
- 报表中心、数据看板和导出。
- 企业微信免登、JS-SDK 和应用消息的实际接入。
- 完整主题编辑器；不开放字体、圆角和任意布局配置。
- 依赖 PWA 安装、Web Push 或 Service Worker 才能完成的核心流程。

## 4. 信息架构

### 4.1 底部导航

| 菜单 | 责任 | 角标 |
|---|---|---|
| 工作台 | 常用审批应用、全部应用、最近流程 | 无 |
| 待办 | 待我处理、我发起的、已处理 | PENDING 待办数量 |
| 我的 | 个人资料、工作概览、常用应用、草稿、账号与安全 | 草稿数量可选 |

底部导航固定在安全区上方，高度由组件高度和 `env(safe-area-inset-bottom)` 共同计算。二级、详情和表单页面不显示底部导航，使用顶部返回导航。

### 4.2 路由建议

```text
/login                         账号登录
/                              重定向 /workbench
/workbench                     工作台
/apps                          全部应用
/apps/favorites                常用应用管理
/forms/:code                   动态表单填写
/forms/:code/assignees         自选审批人
/forms/:code/confirm           提交确认
/forms/:code/success           提交成功
/tasks                         待办中心
/tasks/:taskId                 审批详情
/processes/:instanceId         流程详情
/drafts                        草稿箱
/profile                       我的
/profile/security              账号与安全
/error/offline                 离线恢复
/error/forbidden               无权访问
/error/unavailable             服务不可用
```

`/tasks` 通过 URL query 保留当前视图和筛选，例如 `?view=pending&type=leave`。返回详情后列表位置和筛选条件必须恢复。

## 5. 关键页面规格

### 5.1 登录

- 显示可配置浅色 LOGO、应用名称、登录页标题和页脚。
- 首期提供用户名、密码和登录按钮。
- 企业微信免登位置保留，但未配置时不显示不可用按钮。
- 登录成功后优先返回原始目标页面，否则进入工作台。
- 登录失败不区分“用户不存在”和“密码错误”。

### 5.2 工作台

- 页眉：可配置页眉标题、企业名称、用户头像。
- 欢迎区：问候语和待办数量，不使用营销文案或大面积装饰。
- 常用应用：最多 8 个，2 行 × 4 列；支持员工自行排序。
- 最近流程：最多 3 条，展示模板名、当前节点、状态和更新时间。
- “全部应用”进入应用目录，不在工作台承载复杂筛选。
- 应用入口来自已发布且当前用户可发起的表单，不硬编码审批类型。

### 5.3 全部应用

- 支持按名称搜索。
- 支持最近使用、业务分类和全部应用。
- 首期分类来源于表单 `settings` 中的受控分类 code；没有分类时进入“其他”。
- “管理”只管理当前用户常用应用，不允许修改表单定义。
- 无可发起应用时展示空状态和权限说明。

### 5.4 常用应用管理

- 已添加和可添加分区展示。
- 最多选择 8 个。
- 已添加项支持拖动排序和移除。
- 结果保存到服务端用户偏好，跨设备同步。
- 保存失败保留当前编辑状态，并允许重试。

### 5.5 动态表单填写

- 使用移动字段注册表渲染结构化 `SchemaNode[]`。
- 字段值始终以 `SchemaNode.id` 为 key。
- 支持当前桌面端 14 种字段的移动版本。
- 布局字段在移动端允许降级：`span_layout` 在窄屏转为单列；`table_list` 使用行卡片而非横向表格。
- 文件字段首期必须接入真实上传服务；不能继续只保存文件名。
- 输入以节流方式保存本地恢复副本，用户主动保存时写服务端草稿。
- 离开存在未保存更改的页面时提示确认。
- 底部主操作固定，键盘弹出时不得遮挡当前字段和按钮。

### 5.6 自选审批人

- 仅当流程存在 `SELF_SELECT` 节点时出现。
- 按节点逐项展示单选或多选规则。
- 支持员工搜索和最近选择，不提供独立通讯录入口。
- 选人结果使用 `{ [processNodeId]: userId[] }`。
- 必须修复现有后端使用 `props.id` 而非 `node.id` 查询自选审批人的问题。

### 5.7 提交确认

- 展示模板、关键字段摘要、附件数量和预期审批链。
- 条件分支无法预判时显示“提交后根据申请内容确定”，不能伪造确定路径。
- 确认提交使用幂等 key，按钮在请求期间不可重复触发。
- 成功进入独立成功页；失败保留表单和选人结果。

### 5.8 待办中心

三个顶部视图：

- `待我处理`：当前用户 PENDING 任务。
- `我发起的`：当前用户启动的流程实例。
- `已处理`：当前用户已经完成审批动作的任务。

列表支持搜索、下拉刷新、分页和类型/状态/时间筛选。任务状态和实例状态必须分别展示，不能把“我已同意”误写成“流程已通过”。

### 5.9 审批详情

- 顶部摘要：模板、发起人、部门、发起时间、当前节点。
- 申请内容：只读动态表单。
- 附件：预览或下载。
- 审批进度：按实例快照展示历史和未来节点。
- 底部操作：更多、驳回、同意。
- 同意意见选填；驳回原因必填。
- 驳回允许选择结束流程或后端返回的合法目标节点。
- 操作完成后更新待办角标、任务列表、实例详情和已处理列表。

首期“更多”只展示后端真正可用且已完成端到端验证的动作。转交、委托、加签和召回在前端与后端契约完整前不得显示。

### 5.10 流程详情

- 使用 `process_snapshot` 展示实例实际执行的流程，不读取当前定义。
- 展示每个节点的处理人、结果、意见和时间。
- 发起人可查看原始表单和附件。
- 只有后端返回 `canWithdraw=true` 时显示撤回。
- 资源访问必须验证发起人、任务参与者或管理员身份。

### 5.11 我的

- 用户头像、显示名、部门和职务。
- 待处理、进行中、本月完成统计。
- 常用应用管理、草稿箱、账号与安全。
- 帮助与反馈、版本信息和退出登录。
- 不提供桌面后台管理入口。

### 5.12 草稿箱

- 展示模板、保存时间和填写进度。
- 支持继续填写和明确删除。
- 草稿与流程实例分离；草稿不写任务历史。
- 模板被停用时草稿只允许查看或删除，并解释无法提交的原因。

### 5.13 账号与安全

- 展示登录账号、修改密码和已登录设备。
- 支持退出当前设备和注销其他设备。
- 预留企业微信绑定状态，首期标记为未启用或完全隐藏。

### 5.14 空、错、加载状态

- 首屏骨架屏结构必须接近最终页面，避免布局跳动。
- 列表无数据、搜索无结果、无权限、离线和服务不可用分别设计。
- 离线时保留未提交表单输入。
- 所有无法自动恢复的错误提供明确操作，不只显示 Toast。

## 6. 视觉设计系统

### 6.1 视觉原则

采用“清晰秩序”方向：

- 页面背景使用浅中性色，主要内容使用白色表面。
- 卡片只用于独立信息组，不嵌套卡片。
- 主色只承担品牌、选中和主要操作。
- 成功、警告、失败和中性状态使用固定语义色。
- 阴影极轻，主要依靠间距、背景和分隔线建立层级。
- 内容密度适合重复办公，不使用营销型大标题和装饰图形。

### 6.2 基础 Token

```css
:root {
  --af-color-primary: #1677ff;       /* 后台可配置 */
  --af-color-success: #31a354;       /* 固定 */
  --af-color-warning: #fa8c16;       /* 固定 */
  --af-color-danger: #ff4d4f;        /* 固定 */
  --af-color-text: #202830;
  --af-color-text-secondary: #69737d;
  --af-color-muted: #8b949e;
  --af-color-bg: #f4f6f8;
  --af-color-surface: #ffffff;
  --af-color-border: #e7eaee;
  --af-radius-control: 6px;
  --af-radius-surface: 8px;
  --af-space-1: 4px;
  --af-space-2: 8px;
  --af-space-3: 12px;
  --af-space-4: 16px;
  --af-space-6: 24px;
}
```

主色派生色通过颜色算法生成，禁止管理员逐个编辑。发布前校验主按钮文字对比度和选中态对比度。

### 6.3 尺寸与可访问性

- 普通正文不小于 14px，辅助信息不小于 12px。
- 可交互区域至少 44 × 44px。
- 顶部导航视觉高度 44px，另加安全区。
- 底部导航视觉高度 50–56px，另加安全区。
- 表单输入、按钮和固定工具栏不能被软键盘遮挡。
- 正文对比度至少 4.5:1，大文字至少 3:1。
- 适配 360px 到 430px 手机宽度及系统字体放大。

## 7. 品牌外观管理

### 7.1 桌面路由

新增 `系统设置 → 品牌外观`，建议路由 `/settings/branding`。现有 `/settings/company` 继续负责企业基础信息，不混入主题发布状态。

### 7.2 可配置项

- 应用名称。
- 企业名称。
- 品牌主色。
- 浅色背景 LOGO。
- 深色背景 LOGO。
- favicon。
- 登录页背景图。
- 移动端页眉标题。
- 登录页标题。
- 页脚版权文案和是否显示。

不开放字体、圆角、状态色、底部菜单结构和页面布局。

### 7.3 编辑和发布

- 配置采用草稿/发布模式。
- 管理员可实时预览移动工作台和登录页。
- 保存草稿不影响线上用户。
- 发布前校验字段长度、主色对比度、资源类型、大小和尺寸。
- 保存发布记录：版本、操作人、时间和完整配置快照。
- 支持恢复任意已发布版本，恢复行为生成新版本而不是覆盖历史。
- 客户端品牌配置使用版本号/ETag 缓存，发布后可快速失效。

### 7.4 数据模型建议

```text
t_brand_config
  id                  BIGSERIAL PK
  version             INT NOT NULL
  status              DRAFT | PUBLISHED | ARCHIVED
  app_name            VARCHAR(64)
  company_name        VARCHAR(128)
  primary_color       VARCHAR(16)
  mobile_header_title VARCHAR(64)
  login_title         VARCHAR(64)
  footer_text         VARCHAR(255)
  show_login_footer   BOOLEAN
  logo_light_url      VARCHAR(512)
  logo_dark_url       VARCHAR(512)
  favicon_url         VARCHAR(512)
  login_bg_url        VARCHAR(512)
  created_by          BIGINT FK t_user
  created_at          TIMESTAMPTZ
  published_by        BIGINT FK t_user NULL
  published_at        TIMESTAMPTZ NULL
```

首期系统仍是单租户，因此只允许一个当前 PUBLISHED 版本。未来多租户化时再增加 `company_id`，不能提前用前端参数伪造租户隔离。

## 8. 移动端工程架构

### 8.1 技术选型

- React 19。
- TypeScript strict。
- Vite。
- React Router。
- Ant Design Mobile。
- TanStack Query。
- Zustand，仅用于轻量客户端状态。
- Vitest + Testing Library。
- Playwright。
- 可选 Vite PWA 插件，但核心流程不能依赖 PWA 能力。

### 8.2 目录结构

```text
mobile/
├── src/
│   ├── app/                 路由、Provider、App Shell、错误边界
│   ├── features/
│   │   ├── auth/
│   │   ├── branding/
│   │   ├── workbench/
│   │   ├── forms/
│   │   ├── tasks/
│   │   ├── processes/
│   │   └── profile/
│   ├── shared/
│   │   ├── api/             HTTP、DTO、错误转换、query keys
│   │   ├── ui/              无业务归属的共享组件
│   │   ├── storage/         会话、恢复草稿、用户偏好
│   │   ├── platform/        BrowserAdapter / WeComAdapter
│   │   ├── telemetry/       错误与性能上报
│   │   └── utils/
│   └── main.tsx
├── e2e/
├── public/
├── package.json
├── tsconfig.json
└── vite.config.ts
```

### 8.3 边界规则

- 页面只能通过 feature service/query 调业务 API。
- 页面不能直接读取 `localStorage` 或解析 JSONB 字符串。
- 后端实体不能成为移动端公开契约，必须转换为 DTO。
- 共享 UI 不依赖具体 feature。
- 页面不能判断企业微信环境，只能调用 `PlatformAdapter`。
- 品牌配置只通过 `BrandProvider` 转换为受控 CSS Variables。

## 9. 后端与 API 设计

### 9.1 必须先修复的现有问题

1. 普通用户被类级 admin 权限阻止读取已发布表单和流程。
2. JSONB 实体字段以字符串形式返回，运行时页面未统一解析。
3. `SELF_SELECT` 使用错误 key。
4. 实例详情和历史缺少资源级授权。
5. `IdempotencyService` 尚未接入启动和审批 API。
6. CORS 配置项没有真正接入 SecurityConfig。
7. 前端和后端开发端口、数据库凭据默认值不一致。

### 9.2 移动 API 建议

| 方法 | 路径 | 权限 | 责任 |
|---|---|---|---|
| GET | `/api/public/branding` | 公开 | 当前已发布品牌 DTO |
| GET | `/api/mobile/bootstrap` | 登录 | 用户、品牌版本、待办数、常用应用、最近流程 |
| GET | `/api/mobile/apps` | 登录 | 当前用户可发起的已发布应用 |
| PUT | `/api/mobile/preferences/apps` | 登录 | 保存常用应用和顺序 |
| GET | `/api/mobile/forms/{code}` | 登录 | 结构化移动表单 DTO |
| GET | `/api/mobile/forms/{code}/start-context` | 登录 | 自选节点、可预览流程、发起权限 |
| POST | `/api/mobile/drafts` | 登录 | 新建草稿 |
| PUT | `/api/mobile/drafts/{id}` | 本人 | 更新草稿 |
| DELETE | `/api/mobile/drafts/{id}` | 本人 | 删除草稿 |
| GET | `/api/mobile/drafts` | 登录 | 我的草稿 |
| POST | `/api/mobile/instances` | 登录 + 幂等 | 提交并启动流程 |
| GET | `/api/mobile/instances` | 登录 | 我发起的实例 |
| GET | `/api/mobile/instances/{id}` | 参与者/admin | 实例、表单、任务和快照视图 |
| POST | `/api/mobile/instances/{id}/withdraw` | 发起人 + 幂等 | 撤回 |
| GET | `/api/mobile/tasks` | 登录 | 待我处理或已处理 |
| GET | `/api/mobile/tasks/{id}` | 当前处理人/admin | 审批详情 DTO |
| POST | `/api/mobile/tasks/{id}/approve` | 当前处理人 + 幂等 | 同意 |
| POST | `/api/mobile/tasks/{id}/reject` | 当前处理人 + 幂等 | 驳回 |

管理 API：

| 方法 | 路径 | 责任 |
|---|---|---|
| GET | `/api/admin/branding` | 当前草稿、当前发布版本和历史摘要 |
| POST | `/api/admin/branding/drafts` | 保存草稿 |
| POST | `/api/admin/branding/{id}/publish` | 校验并发布 |
| POST | `/api/admin/branding/{version}/restore` | 恢复历史版本为新版本 |
| POST | `/api/admin/branding/assets` | 上传并校验品牌资源 |

### 9.3 DTO 原则

- JSON 字段在服务端转换为对象/数组。
- 不返回 `passwordHash`、数据库内部删除标记或不必要的外键。
- 每个操作 DTO 返回 `allowedActions` 或布尔能力，前端不复制后端业务规则。
- 错误统一为 `{ code, message, traceId, fieldErrors?, retryAfter? }`。
- 列表使用游标或稳定分页，不能长期返回无界数组。

## 10. 认证与企业微信扩展

### 10.1 首期认证

- 短期 access token 只保存在内存。
- refresh token 使用 `HttpOnly`、`Secure`、`SameSite=Lax` cookie。
- refresh token 轮换，服务端保存可撤销会话。
- 页面刷新时通过 refresh endpoint 恢复 access token。
- 支持列出和注销登录设备。
- 从现有 24 小时 localStorage JWT 迁移时保留桌面端兼容期，但移动端不得新增长期 localStorage token。

### 10.2 平台适配器

```ts
interface PlatformAdapter {
  kind: 'browser' | 'wecom';
  trySilentLogin(): Promise<PlatformLoginResult | null>;
  openFile(file: MobileFile): Promise<void>;
  closePage(): void;
  getEnvironment(): PlatformEnvironment;
}
```

首期只实现 `BrowserAdapter`。二期增加 `WeComAdapter`，业务 feature 不修改。

### 10.3 企业微信二期

- 配置信任域名和 OAuth 回调。
- 后端使用 code 换企业微信用户身份并映射 `t_user`。
- `corpSecret` 只在后端保存。
- 待办和结果通知由后端调用企业微信应用消息 API。
- JS-SDK 签名由后端生成。
- 企业微信 WebView 不支持的能力回退到 BrowserAdapter。

## 11. 核心数据流

### 11.1 应用启动

1. 请求公开品牌配置；失败时使用内置默认品牌。
2. 应用品牌 CSS Variables、LOGO 和 document title。
3. 恢复 access token；失败进入登录。
4. 请求 `/api/mobile/bootstrap`。
5. 渲染 App Shell、工作台和待办角标。

公开品牌配置可跨会话缓存。用户数据必须按用户 ID 隔离，退出后清除。

### 11.2 发起流程

1. 获取结构化表单和 start context。
2. 初始化本地恢复副本或服务端草稿。
3. 用户填写，客户端做即时校验。
4. 如有 `SELF_SELECT`，按节点收集审批人。
5. 展示提交确认。
6. 生成 idempotency key 并提交。
7. 成功后删除草稿，刷新 bootstrap、我发起列表和实例详情。
8. 进入提交成功页。

服务端在同一事务创建 FormData、ProcessInstance、流程快照和首批任务。

### 11.3 处理任务

1. 打开任务详情，后端验证当前处理人。
2. 获取表单、实例快照、历史和 allowed actions。
3. 同意或驳回时生成 idempotency key。
4. 请求成功后刷新待办数、待办列表、已处理列表和实例详情。
5. `409` 时重新获取任务并展示最新状态。

## 12. 错误与恢复

| 场景 | 行为 |
|---|---|
| 离线 | 保留输入，显示离线状态，用户主动重试 |
| 401 | 刷新令牌一次；失败后带 returnUrl 登录 |
| 403 | 无权限页面，不重复跳转 |
| 404 | 区分模板停用、任务不存在和实例不存在 |
| 409 | 提示数据已变化，刷新相关资源 |
| 422 | 映射 fieldErrors 或显示业务错误 |
| 429 | 显示 retryAfter，暂时禁用操作 |
| 500 | 展示友好文案和 traceId |
| chunk 失败 | 自动重载一次，仍失败进入恢复页 |
| 上传失败 | 保留队列和表单，不丢弃其他已上传资源 |

所有不可恢复错误由应用级 ErrorBoundary 接管。Toast 只用于轻量反馈，不能承载需要用户决策的错误。

## 13. 安全

- 全站 HTTPS。
- Cookie 认证配合 CSRF 防护；写请求携带 CSRF token。
- CSP 禁止任意脚本源和内联脚本。
- 用户输入输出统一转义；富文本采用严格 allowlist。
- 实例、任务、草稿和附件执行资源级授权。
- 品牌上传校验 MIME、扩展名、文件头、大小、尺寸和 SVG 内容。
- 品牌发布、任务操作、登录设备和权限拒绝记录审计日志。
- 企业微信 secret、JWT key 和存储密钥来自安全配置，不进入前端或仓库。
- 依赖漏洞和许可证检查进入 CI。

## 14. 性能与体验指标

- 首屏使用单个 bootstrap 聚合请求，避免瀑布请求。
- 路由和非首屏 feature 懒加载。
- 首屏 JS gzip 目标小于 250 KB。
- 中端移动设备 FCP 小于 1.8 秒，可交互时间小于 2.5 秒。
- 列表 API p95 小于 500ms，审批写操作 p95 小于 800ms（不含外部通知）。
- 列表超过 50 项使用分页或虚拟列表。
- 图片按展示尺寸压缩，品牌资源使用内容 hash 和长缓存。
- 页面切换只使用 150–220ms 的轻量过渡。
- 弱网下不自动反复重试写请求。

## 15. 可观测性

- 前端 API 请求携带 request ID，并记录后端 traceId。
- 采集页面加载、路由错误、JS 异常、API 失败和关键操作耗时。
- 不上报密码、表单敏感内容、token 和完整附件 URL。
- 后端将 traceId 写入日志 MDC，形成前后端关联。
- 审批操作成功、失败、幂等重放和权限拒绝可审计。
- 品牌发布记录配置版本和操作人。

## 16. 测试策略

### 16.1 移动端

- 单元：DTO、错误映射、权限能力、表单校验、主题 token、PlatformAdapter。
- 组件：动态字段、列表卡片、时间线、确认弹层、空/错/加载状态。
- 契约：OpenAPI schema 与移动 DTO 一致。
- E2E：登录 → 发起 → 审批 → 结果完整闭环。
- E2E：草稿恢复、撤回、驳回、409、离线恢复、品牌切换。
- 视觉回归：360×800、375×812、390×844、430×932。
- 可访问性：触控尺寸、键盘焦点、对比度和屏幕阅读标签。

### 16.2 后端

- 普通员工读取已发布表单，不能读取草稿。
- 实例和任务资源级权限。
- start/approve/reject/withdraw 幂等。
- refresh token 轮换、撤销和设备退出。
- 品牌草稿、发布、历史恢复和并发发布。
- 文件上传类型欺骗、超限和恶意 SVG。
- `SELF_SELECT`、流程快照和条件分支集成测试。
- PostgreSQL Testcontainers 覆盖 Flyway V1 到最新版本。

### 16.3 发布门禁

- Biome 无 error。
- TypeScript 零错误。
- 单元、后端、契约和核心 E2E 全部通过。
- 移动生产构建成功。
- 关键页面截图无重叠、截断和不可见操作。
- Lighthouse 性能、可访问性和最佳实践不低于 90。
- 数据库迁移只追加，不修改历史迁移。

## 17. 部署

推荐同域：

```text
https://flow.example.com/           桌面后台
https://flow.example.com/mobile/    移动端
https://flow.example.com/api/       后端 API
```

- 桌面和移动端独立构建、独立缓存策略。
- `/mobile/*` 回退到移动端 `index.html`。
- `/api/*` 反向代理后端。
- 品牌资源放在受控对象存储或文件服务，URL 由后端生成。
- PWA manifest 使用发布后的应用名称、主色和图标生成或刷新。
- 企业微信可信域名使用同一生产域名。

## 18. 验收标准

1. 管理员可以配置品牌、保存草稿、实时预览、发布和恢复历史版本。
2. 未登录用户能看到已发布品牌，不能读取品牌草稿。
3. 普通员工可以完成登录、查看应用、保存草稿和发起审批。
4. 审批人可以完成待办、同意、驳回和已处理查询。
5. 发起人可以查看快照流程，并在后端允许时撤回。
6. 普通用户不能读取无关实例、任务、草稿和附件。
7. 重复点击或网络重放不会重复启动或重复审批。
8. 360px 小屏和主流安全区下无重叠、溢出和按钮遮挡。
9. 弱网或刷新不会丢失未提交表单。
10. 全部发布门禁通过后才能部署。

## 19. 建议实施顺序

1. 修复现有权限、JSONB DTO、SELF_SELECT、实例授权和配置不一致。
2. 建立品牌配置后端、桌面管理页和公开品牌读取。
3. 创建 `mobile/` 基础工程、App Shell、主题和认证。
4. 实现工作台、应用目录和用户偏好。
5. 实现移动动态表单、草稿、上传和流程启动。
6. 实现待办、审批详情、同意/驳回和实例进度。
7. 完成账号安全、错误恢复、可观测性和性能优化。
8. 补齐契约、E2E、视觉回归和发布门禁。
9. 二期增加企业微信适配器、免登和应用消息。

