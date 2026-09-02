# AntFlow

AntFlow 是一套自研审批平台，包含可视化表单设计、流程设计、审批运行时、桌面管理端和移动端。项目基于 Spring Boot、PostgreSQL、Ant Design Pro 与 Ant Design Mobile，支持本地一体化 Docker 运行。

## 当前能力

- **表单设计**：文本、选择、人员、媒体、扫码、录音、定位等字段，支持字段校验、节点级隐藏/只读/可编辑权限、模板 JSON 导入导出，以及复用真实移动端渲染器的预览。
- **流程设计**：条件分支、并行网关、延时/触发节点、发布前静态校验；流程树最多 50 层，角色和发起人自选审批人最多 100 人。
- **审批方式**：会签、或签、比例签、顺签，支持指定人、角色、直属主管、连续多级主管、表单人员字段、发起人本人和发起人自选。
- **运行时**：定义版本快照、表单数据修订、节点实例与审批轮次；新版本不会影响在途实例，审批记录能够关联当时的表单版本。
- **流程操作**：同意、驳回、撤回、原单修改重提、转交、委托代理、前/后加签、同意追回、管理员改派和强制终止。
- **并行与一致性**：ALL/ANY 汇聚、并行驳回仲裁、实例与节点级串行化、乐观锁、幂等校验，以及任务流转、下游生成、历史记录和事务消息的本地事务一致性。
- **桌面管理端**：表单/流程设计、组织和角色权限、待办/已办/我发起、审批记录、报表、审计、OIDC 身份提供方、企业微信集成和流程运行监控。桌面任务及实例列表在 SQL 权限过滤后分页。
- **移动端**：工作台、动态表单、草稿、自选审批人、任务处理、流程详情、审批记录、待修改重提、站内通知、SSE 实时刷新、离线恢复和品牌配置。
- **身份与安全**：本地账号、标准 OIDC（Authorization Code + PKCE）和企业微信内部应用免登最终统一进入 JWT 会话；外部身份只绑定已有启用用户，不继承外部角色。另有登录限流、细粒度 RBAC、接口级数据权限和可归档审计事件。
- **企业微信入口**：通讯录同步、应用内免登、JS-SDK 选图/拍照/录音/扫码/定位/附件预览/关闭页面，以及带重试与去重的审批文本卡片消息。
- **原生表单能力**：扫码、录音和定位在普通桌面/移动浏览器中可直接使用，企业微信 JS-SDK 只是运行时适配器；浏览器坐标保存为 WGS84，企业微信坐标保存为 GCJ02。
- **自动化与存储**：持久化延时/触发任务、失败重试、Webhook SSRF 防护、MinIO 附件与审计归档、后台媒体处理。

## 技术栈

- Java 17、Spring Boot 3、MyBatis-Plus、Flyway、PostgreSQL 17
- React 19、Umi Max、Ant Design Pro、Ant Design Mobile、TanStack Query
- MinIO、Nginx、Docker Compose
- Vitest、Playwright、Testcontainers

## 一体化 Docker 运行（推荐）

### 前置要求

- Docker Desktop 或兼容的 Docker Engine + Compose
- Java 17、Maven 3.9
- Node.js 22+

### 1. 配置本地密钥

`.env.docker.local` 包含密钥且不能提交。首次使用时先将它加入当前仓库的本地 Git 排除文件：

```powershell
Add-Content .git/info/exclude '.env.docker.local'
```

在仓库根目录创建 `.env.docker.local`，并把所有 `replace-with-*` 替换为独立的高强度随机值：

```dotenv
POSTGRES_PASSWORD=replace-with-postgres-password
MINIO_ROOT_USER=antflow-local
MINIO_ROOT_PASSWORD=replace-with-minio-password
JWT_SECRET=replace-with-at-least-32-random-characters
AUDIT_ARCHIVE_ENCRYPTION_SECRET=replace-with-audit-encryption-secret
ANTFLOW_INTEGRATION_ENCRYPTION_KEY=replace-with-integration-encryption-key
ANTFLOW_PUBLIC_BASE_URL=https://approval.example.com
ANTFLOW_OIDC_ALLOWED_HOSTS=id.example.com,login.example.com
BACKUP_ENCRYPTION_SECRET=replace-with-at-least-32-random-characters
```

### 2. 构建生产产物

`Dockerfile.local` 只复制本地已经生成的 JAR 和 `dist`，因此每次部署代码变更前必须先构建对应产物：

```powershell
mvn -B -f backend/pom.xml -DskipTests package

npm --prefix frontend ci --no-audit --no-fund
npm --prefix frontend run build

npm --prefix mobile ci --no-audit --no-fund
npm --prefix mobile run build
```

### 3. 启动服务

```powershell
docker compose --env-file .env.docker.local up -d --build
docker compose --env-file .env.docker.local ps
```

访问地址：

- 桌面管理端：<http://127.0.0.1:7070/>
- 移动端：<http://127.0.0.1:7070/mobile/login>
- 后端健康检查：<http://127.0.0.1:7070/actuator/health>
- 初始开发账号：`admin / ant.design`、`bob / ant.design`

首次启动会自动执行 Flyway 迁移。PostgreSQL 和 MinIO 分别使用 `antflow-local_postgres_data`、`antflow-local_minio_data` 持久卷。

### 更新并重新部署

后端、桌面端或移动端代码改变后，先重新执行上面的对应构建命令，再重建后端和 Web 服务：

```powershell
docker compose --env-file .env.docker.local up -d --build backend web
Invoke-RestMethod http://127.0.0.1:7070/actuator/health
```

不要执行 `docker compose down -v`，除非明确需要删除 PostgreSQL 和 MinIO 的全部本地数据。

系统设置中的“系统备份”每天默认 02:30 生成 PostgreSQL、附件和审计归档的
AES-GCM 加密备份并保留 30 天，文件存放在宿主机 `backups/`。恢复必须停机执行：

```bash
BACKUP_ENCRYPTION_SECRET='与备份时相同的密钥' backend/backup/restore-backup.sh backups/antflow-YYYYMMDD-HHMMSS.afbackup --confirm
```

## 源码开发

源码模式需要本机已有可访问的 PostgreSQL 数据库 `antflow` 和 MinIO。自定义数据库连接时，JDBC URL 必须保留 `stringtype=unspecified`。

### 后端

```powershell
Set-Location backend
$env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/antflow?stringtype=unspecified'
$env:SPRING_DATASOURCE_USERNAME='postgres'
$env:SPRING_DATASOURCE_PASSWORD='your-password'
$env:MINIO_ENDPOINT='http://localhost:9000'
$env:MINIO_ACCESS_KEY='minioadmin'
$env:MINIO_SECRET_KEY='your-minio-password'
mvn -B spring-boot:run
```

后端默认监听 `8080`，健康检查为 <http://localhost:8080/actuator/health>。

### 桌面端

```powershell
Set-Location frontend
npm ci --no-audit --no-fund
npm start
```

桌面开发服务器监听 <http://localhost:8000>，`/api/*` 默认代理到 `http://localhost:8080`，`/mobile/*` 默认代理到 `http://localhost:5173`。

### 移动端

```powershell
Set-Location mobile
npm ci --no-audit --no-fund
npm run dev
```

移动端监听 <http://localhost:5173/mobile/login>，`/api/*` 默认代理到 `http://localhost:8080`。更详细的移动端说明见 [mobile/README.md](mobile/README.md)。

## 质量检查

### 后端

```powershell
mvn -B -f backend/pom.xml test
```

涉及 PostgreSQL 事务、并行仲裁或分页权限的改动还应确保 Docker 可用，以运行 Testcontainers 集成测试。

### 桌面端

```powershell
Set-Location frontend
npm run lint
npm test
npm run build
npx antd lint ./src
```

### 移动端

```powershell
Set-Location mobile
npm run check:enterprise
npm run test:e2e
```

`check:enterprise` 依次执行 lint、单元测试、类型检查、生产构建和 bundle 预算检查。

当前 CI 行为：后端测试和桌面端构建为阻断门禁；桌面端 Biome/TypeScript 检查暂为非阻断；移动端 lint、测试、构建和 bundle 预算均为阻断门禁。

## 写路径压测

写路径压测覆盖流程发起、同意、驳回、并行网关和转交，并对比 Hikari 连接池 10/20。脚本会暂时停止当前本地 Compose 服务，使用隔离的临时 PostgreSQL 卷，结束后恢复原服务；结果写入被忽略的 `_perf/write-load/`。

```powershell
.\backend\perf\run-write-load.ps1 -Quick
```

去掉 `-Quick` 可执行完整对比。鉴权读路径的历史容量报告见 [docs/performance-load-test-2026-08-24.md](docs/performance-load-test-2026-08-24.md)。

## 分页接口约定

桌面任务和实例接口使用服务端分页：

```http
GET /api/tasks?view=pending&page=1&size=20
GET /api/instances?scope=mine&page=1&size=20
```

响应统一包含：

```json
{
  "records": [],
  "total": 0,
  "page": 1,
  "size": 20
}
```

权限过滤在 SQL 分页之前完成；`scope=mine` 只返回当前用户发起且不处于待修改阶段的实例，`scope=authorized` 按管理员、任务参与、抄送、表单授权和数据范围返回可见实例。

## 目录结构

```text
ant-flow/
├── backend/                 # Spring Boot 后端、迁移、测试和压测脚本
├── frontend/                # Umi Max 桌面管理端
├── mobile/                  # Vite 移动端，部署基路径 /mobile/
├── infra/                   # Nginx 配置
├── docs/                    # 设计、验收和性能文档
├── compose.yaml             # 本地一体化运行栈
├── compose.write-load.yaml  # 隔离写路径压测栈
├── Dockerfile.local         # 本地产物镜像
└── README.md
```

## 已知边界

- 当前仍是单租户业务边界，没有完整的跨租户隔离模型。
- OIDC 只支持标准 Authorization Code + PKCE；不自动创建本地用户，也不接收外部角色授权。
- 企业微信仅支持一个启用的内部自建应用 Corp 免登入口；不提供桌面端企业微信扫码登录，也未实现服务商代开发授权。
- 企业微信真实验收依赖公网 HTTPS 域名、可信域名配置和企业测试凭证；密钥只通过环境变量或管理页配置，不写入 Git。
- 浏览器摄像头、麦克风和定位在生产环境依赖 HTTPS（`localhost`/`127.0.0.1` 本地调试除外），并需要用户授予对应权限。
- 移动端不提供表单/流程设计器和组织管理。
- 超时计算尚未接入节假日工作日历。
- 核心审批流程不依赖 PWA 安装、Service Worker 或 Web Push。

## 相关文档

- [移动端运行说明](mobile/README.md)
- [移动端企业级验收记录](docs/mobile-enterprise-verification.md)
- [鉴权读路径性能报告](docs/performance-load-test-2026-08-24.md)
- [历史交接记录](docs/session-summary-2026-07-31.md)
- 开发约定与模块命令：`CLAUDE.md`、`codex.md`
