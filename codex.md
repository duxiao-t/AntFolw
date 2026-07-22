# codex.md

Agent / Codex 在本仓库工作时的速查说明。更完整的领域约定见 `CLAUDE.md`。

## 仓库结构

```text
ant-flow/
├── backend/     # Spring Boot 3 + Java 17 + MyBatis-Plus + Flyway + PostgreSQL
├── frontend/    # Umi Max 4 桌面管理端（表单/流程设计、组织、任务）
├── mobile/      # 独立移动端（Vite + React + Ant Design Mobile），base `/mobile/`
├── infra/       # docker-compose、nginx 示例
└── docs/        # superpowers specs/plans、企业级验收记录
```

**先 `cd` 进模块再执行命令**，不要在仓库根直接跑 `mvn` / `npm`。

## 本地运行

### 数据库

```powershell
Set-Location infra
docker compose up -d
```

### 后端

默认 `8080`；移动端联调常用 `8081`：

```powershell
Set-Location backend
$env:PORT='8081'
mvn -B spring-boot:run
# 测试
mvn -B test
```

种子账号：`admin / ant.design`、`bob / ant.design`。

### 桌面前端

```powershell
Set-Location frontend
npm ci
npm start          # http://localhost:8000，代理 /api → 后端
npm run biome:lint
npm test
npm run tsc
npm run build
```

### 移动端

```powershell
Set-Location mobile
npm ci
npm run dev        # http://localhost:5173/mobile/login ，Vite base=/mobile/
npm run check:enterprise   # lint + unit + build + bundle≤250KiB gzip
npm run test:e2e           # Playwright 四视口；本地 workers=1
```

E2E 默认端口优先 `5174`（`E2E_PORT`），避免与本机 5173 冲突。

## 移动端架构要点

- **部署**：同域 `/mobile/` 静态资源 + `/api/` 后端；生产把 `mobile/dist/` 挂到站点 `mobile/` 子目录。
- **壳层**：底部三栏工作台 / 待办 / 我的；详情与表单页无底栏。
- **路由**（basename `/mobile`）：
  - `/login`
  - `/workbench` `/tasks` `/profile`
  - `/apps` `/apps/favorites`
  - `/forms/drafts` `/forms/:code` `/forms/:code/self-select` `/forms/:code/confirm` `/forms/:code/success/:instanceId`
  - `/tasks/:taskId` `/processes/:instanceId` `/profile/security`
- **状态**：TanStack Query + feature store；access token 内存态，刷新走统一 HTTP 包装。
- **品牌**：`BrandProvider` 拉 `/api/public/branding`，非法色/失败用内置 fallback，不接受任意 CSS。
- **平台**：`PlatformAdapter`（Browser 实现；WeCom 二期），**页面禁止**直接判断企业微信环境。
- **安全**：实例/任务/附件均需归属校验；上传白名单类型与大小；发起/审批支持 Idempotency-Key。

## 品牌配置

- 移动端只消费**已发布**品牌 DTO → CSS variables。
- 桌面端品牌编辑/发布能力以平台基础分支相关提交为准；客户端始终可 fallback。
- 默认主色企业级对比：`#0b57d0`。

## 已知非目标（移动端一期）

- 手机端表单/流程设计、组织权限管理
- 企业微信免登 / JS-SDK / 应用消息（仅 adapter 边界）
- 完整主题编辑器、报表看板
- 依赖 PWA 安装或 Service Worker 才能完成的核心审批路径

## 验收文档

见 `docs/mobile-enterprise-verification.md`。

## Git 提交格式

中文约定：`类型(范围): 描述`  
示例：`功能(移动端): …` / `测试(移动端): …` / `文档: 更新企业级移动端运行与验收说明`  
作者历史常用：`AntFlow Bot <bot@antflow.local>`。
