# AntFlow

> **仓库主 README**｜企业级表单与审批平台：可视化表单设计、流程编排与审批运行时，桌面管理端 + 移动审批端开箱即用，一体化 Docker 私有化部署。

[![CI](https://github.com/duxiao-t/AntFolw/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/duxiao-t/AntFolw/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![React](https://img.shields.io/badge/React-19-149eca)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-336791)
![Node](https://img.shields.io/badge/Node.js-22%2B-339933)

## 目录

- [一、项目简介与定位](#一项目简介与定位)
- [二、核心特性与适用场景](#二核心特性与适用场景)
- [三、界面预览](#三界面预览)
- [四、环境依赖](#四环境依赖)
- [五、安装与启动](#五安装与启动)
- [六、快速上手示例](#六快速上手示例)
- [七、配置说明](#七配置说明)
- [八、目录结构](#八目录结构)
- [九、常见问题（FAQ）](#九常见问题faq)
- [十、避坑指南](#十避坑指南)
- [十一、CI 与质量门禁](#十一ci-与质量门禁)
- [十二、已知边界](#十二已知边界)
- [十三、相关文档](#十三相关文档)
- [十四、待补充信息](#十四待补充信息)

---

## 一、项目简介与定位

AntFlow 是一套自研的**企业表单与审批平台**。它把「表单设计 → 流程编排 → 发起 → 审批 → 归档与报表」整条链路放在同一个仓库里：后端是模块化单体（Spring Boot 3），同时对外提供桌面管理端（Umi Max + Ant Design Pro）与移动审批端（Vite + Ant Design Mobile）。

**定位与取舍：**

| 维度 | 说明 |
| --- | --- |
| 部署形态 | 面向**私有化部署**的内部审批场景。一体化 Docker 起停，业务数据、附件、审计归档全部落在自己的 PostgreSQL 与 MinIO 里 |
| 编排能力 | 表单字段、流程节点、审批方式、超时与触发器全部由业务侧配置，**不需要写代码** |
| 安全模型 | 三层鉴权：端点能力 → 资源授权 → 数据范围；配合审计事件、审计归档与流程事务消息重放 |
| 数据边界 | 当前是**单租户**业务边界，用「公司 / 部门 / 角色 + 数据范围」做内部分权，不提供跨租户隔离模型 |
| 适用规模 | 中小团队到中大型企业的内部审批（请假、报销、采购、合同、入职、用印等） |

一句话概括：给「不想为每类审批各买一套系统」的团队，一套**自己可改、自己可部署**的审批底座。

> 要改代码请先读 [`AGENTS.md`](AGENTS.md)（开发约定、不变量与自检清单）。

---

## 二、核心特性与适用场景

### 2.1 核心特性

| 模块 | 能力 |
| --- | --- |
| **表单设计** | 文本、选择、人员、媒体（图片/视频/音频）、扫码、录音、定位等字段；字段校验、节点级隐藏/只读/可编辑权限；模板 JSON 导入导出；复用真实移动端渲染器的预览 |
| **流程设计** | 条件分支、并行网关、延时/触发节点、发布前静态校验；流程树最多 50 层，发起人自选审批人最多 100 人 |
| **审批方式** | 会签、或签、比例签、顺签；审批人来源支持指定人、角色、直属主管、连续多级主管、表单人员字段、发起人本人、发起人自选 |
| **运行时** | 定义版本快照、表单数据修订、节点实例与审批轮次；新版本不影响在途实例，审批记录可关联当时的表单版本 |
| **流程操作** | 同意、驳回、撤回、原单修改重提、转交、委托代理、前/后加签、同意追回、管理员改派、强制终止 |
| **并行与一致性** | ALL/ANY 汇聚、并行驳回仲裁、实例与节点级串行化、乐观锁、幂等校验；任务流转、下游生成、历史记录与事务消息在同一本地事务内 |
| **桌面管理端** | 表单/流程设计、组织与角色权限、待办/已办/我发起、审批记录、报表、审计、OIDC 身份提供方、企业微信集成、流程运行监控 |
| **移动端** | 工作台、动态表单、草稿、自选审批人、任务处理、流程详情、审批记录、待修改重提、站内通知、SSE 实时刷新、离线恢复、品牌配置 |
| **身份与安全** | 本地账号、标准 OIDC（Authorization Code + PKCE）、企业微信内部应用免登，最终统一进入 JWT 会话；登录限流、细粒度 RBAC、接口级数据权限、可归档审计事件 |
| **企业微信** | 通讯录同步、应用内免登、JS-SDK 选图/拍照/录音/扫码/定位/附件预览，带重试与去重的审批文本卡片消息 |
| **原生表单能力** | 扫码、录音、定位在普通桌面/移动浏览器即可使用，企业微信 JS-SDK 只是运行时适配器；浏览器坐标存 WGS84，企业微信坐标存 GCJ02 |
| **自动化与存储** | 持久化延时/触发任务、失败重试、Webhook SSRF 防护、MinIO 附件与审计归档、后台媒体处理（ffmpeg 水印） |

### 2.2 适用场景

- 企业内部的**通用审批中台**：请假、报销、采购、合同、用印、入职离职等，用一套系统配出来。
- **已有系统需要嵌审批**：通过 `/api/**` 接入，或直接走 Webhook 触发与回写。
- **移动审批为主**：员工在手机（含企业微信内）完成填写与审批，管理端做配置与数据看板。
- **合规与审计要求**：需要审批留痕、数据范围隔离、审计事件可归档、消息可重放的场景。
- **需要私有化**：数据不出内网，可离线部署，附件与备份自己掌控。

### 2.3 不太适合的场景

- 需要**多租户 SaaS**（当前单租户边界，见[十二、已知边界](#十二已知边界)）。
- 需要**BPMN 2.0 全量语义**或外部流程引擎互通（本项目是自研流程模型，支持常见审批模式而非 BPMN 全集）。
- 需要开箱即用的**复杂报表 BI**（当前 BI 走只读视图直连库，面向台账类只读查询）。

---

## 三、界面预览

> **待补充**：以下图片需自行截取后放入 `docs/images/`，README 中的链接才会在 GitHub 上正常显示。

| 位置 | 建议文件 | 说明 |
| --- | --- | --- |
| 顶部主图 | `docs/images/hero-desktop.png` | 桌面端工作台 + 待办列表（建议 1440×900） |
| 表单设计器 | `docs/images/form-designer.png` | 表单设计器画布与字段面板 |
| 流程设计器 | `docs/images/process-designer.png` | 流程画布、分支条件抽屉或触发节点抽屉 |
| 审批运行时 | `docs/images/task-approval.png` | 待办详情与审批操作区 |
| 移动端 | `docs/images/mobile-tasks.png` | 移动端工作台/任务列表（建议 390×844） |

截图补全后，把下面代码块中的链接取消注释即可出现在本段：

```markdown
![桌面端工作台](docs/images/hero-desktop.png)
![表单设计器](docs/images/form-designer.png)
![流程设计器](docs/images/process-designer.png)
![移动端任务](docs/images/mobile-tasks.png)
```

**仓库中已有的素材：**

- `_preview/index.html`：**移动端设计预览总目录**（21 个页面的静态预览，已纳入版本控制）。克隆仓库后直接用浏览器打开该文件即可查看全套移动端界面。
- `_preview/*.png`：本机生成的截图素材，但被 `.gitignore` 的 `_preview/*.png` 规则排除，**不会出现在 GitHub 上**。若要在 README 里引用，需要先把文件复制到 `docs/images/` 并调整忽略规则。
- **待补充**：可访问的在线演示地址（或录屏 GIF）。

---

## 四、环境依赖

### 4.1 运行环境（Docker 一体化部署）

| 依赖 | 版本要求 | 说明 |
| --- | --- | --- |
| Docker Engine / Docker Desktop | 建议 24+ | Windows 需开启 Linux 容器；WSL2 后端建议分配 ≥ 4 GB 内存 |
| Docker Compose | v2 及以上 | 使用 `docker compose`（空格）而非 `docker-compose` |
| 磁盘 | ≥ 5 GB 可用 | 后端镜像约 1.6 GB，前端镜像约 105 MB，加数据卷与构建缓存 |
| 内存 | ≥ 2 GB 可跑通，推荐 4 GB 以上 | 后端默认 `-Xmx768m`，另有 PostgreSQL 与 MinIO；内存紧张时镜像构建与压测会比较吃紧 |
| 网络 | 首次部署需要出网 | 拉取基础镜像，且后端镜像内会 apt 安装 ffmpeg 与中文字体 |

### 4.2 源码构建环境（仅在不使用预构建产物时需要）

| 依赖 | 版本要求 | 用途 |
| --- | --- | --- |
| JDK | 17 | 后端编译（`eclipse-temurin` / Oracle OpenJDK 均可） |
| Maven | 3.9+ | 后端打包 |
| Node.js | 22 或更高 | 桌面端与移动端构建（`deploy.sh` 会强制校验） |
| npm | 随 Node 提供 | 依赖安装与构建 |

### 4.3 端口占用

| 端口 | 用途 | 是否必须对外 |
| --- | --- | --- |
| `7070` | 统一入口（桌面端 + 移动端 + API，由 Nginx 反向代理） | 是 |
| `7072` | MinIO S3 API（仅在需要直连对象存储时对外） | 否 |
| `7073` | MinIO 控制台 | 否 |

> PostgreSQL 不映射到宿主机端口，只在 Compose 内部网络暴露，因此**不会和宿主机已有的 5432 冲突**。

---

## 五、安装与启动

### 5.1 方式一：Docker 一体化（推荐）

**第 1 步：克隆代码**

```bash
# HTTPS
git clone -b master --single-branch https://github.com/duxiao-t/AntFolw.git ant-flow
cd ant-flow

# SSH（已配置密钥时）
git clone -b master --single-branch git@github.com:duxiao-t/AntFolw.git ant-flow
```

**第 2 步：创建本地密钥文件**

`.env.docker.local` 已被 `.gitignore` 排除，需要自己在仓库根目录创建，并把所有占位值替换为独立的高强度随机值：

```dotenv
POSTGRES_PASSWORD=<数据库密码>
MINIO_ROOT_USER=antflow-local
MINIO_ROOT_PASSWORD=<MinIO 密码>
JWT_SECRET=<至少 32 位随机字符>
ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD=<初始管理员密码，8-72 字节>
AUDIT_ARCHIVE_ENCRYPTION_SECRET=<审计归档加密密钥>
ANTFLOW_INTEGRATION_ENCRYPTION_KEY=<集成密钥加密密钥>
BACKUP_ENCRYPTION_SECRET=<备份加密密钥，至少 32 位随机字符>
ANTFLOW_PUBLIC_BASE_URL=https://approval.example.com
ANTFLOW_OIDC_ALLOWED_HOSTS=id.example.com,login.example.com
```

**第 3 步：构建产物（镜像内不构建前端）**

`Dockerfile.local` 只负责把产物复制进镜像，因此需要先在宿主机构建：

```bash
# 后端 JAR
mvn -B -f backend/pom.xml -Dmaven.test.skip=true package

# 桌面端
npm --prefix frontend ci --no-audit --no-fund
ANTFLOW_OUTPUT_PATH=dist ANTFLOW_AUTH_CSRF_COOKIE_NAME=antflow-csrf \
  npm --prefix frontend run build

# 移动端
npm --prefix mobile ci --no-audit --no-fund
ANTFLOW_OUTPUT_PATH=dist VITE_AUTH_CSRF_COOKIE_NAME=antflow-csrf \
  npm --prefix mobile run build
```

> `ANTFLOW_AUTH_CSRF_COOKIE_NAME` / `VITE_AUTH_CSRF_COOKIE_NAME` 必须为 `antflow-csrf`。web 镜像在构建末尾会用 `grep` 校验产物中包含该串且不包含 `antflow-test-csrf`，否则镜像构建直接失败——这是防止把测试环境产物推上线的门禁。

**第 4 步：构建镜像并启动**

```bash
docker compose --env-file .env.docker.local up -d --build
docker compose --env-file .env.docker.local ps
```

**第 5 步：访问**

| 入口 | 地址 |
| --- | --- |
| 桌面管理端 | http://127.0.0.1:7070/ |
| 移动端 | http://127.0.0.1:7070/mobile/login |
| 后端健康检查 | http://127.0.0.1:7070/actuator/health |
| MinIO 控制台 | http://127.0.0.1:7073 |
| 接口文档（Swagger UI） | 网关默认**未反代**该路径，且 `prod` profile 默认**不注册**接口文档（`application-prod.yml`）。源码模式下直连后端访问 http://localhost:8080/swagger-ui ；如需在 Docker 部署中临时开启，需同时设置 `ANTFLOW_OPENAPI_ENABLED=true` 并在 `infra/docker/nginx.conf` 为 `/swagger-ui` 与 `/v3/api-docs` 增加反代 |

首次启动会自动执行 Flyway 迁移。管理员账号为 `admin`，密码取自 `ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD`——**该变量只用于初始化待设置凭据，不会覆盖后续改密**。

### 5.2 方式二：Linux 服务器增量部署

服务器在仓库根目录首次启用脚本时执行一次全量构建：

```bash
git pull --ff-only
bash deploy.sh --all
```

之后每次只需一条命令。脚本会对比上次成功部署的提交，只构建并重启发生变化的模块；`package-lock.json` 未变化时复用现有 `node_modules`：

```bash
bash deploy.sh                 # 自动检测变化
bash deploy.sh backend         # 强制重建指定模块
bash deploy.sh frontend mobile
bash deploy.sh --all           # 改了 .env.docker.local 或需要恢复性重建
```

部署版本记录在 `.git/antflow-deployed-revision`，**只有健康检查通过后才更新**，失败可安全重试。

### 5.3 方式三：源码开发模式

需要本机已有可访问的 PostgreSQL（库名 `antflow`）与 MinIO。自定义数据库连接时，**JDBC URL 必须保留 `stringtype=unspecified`**。

```bash
# 后端（默认 8080）
cd backend
SPRING_PROFILES_ACTIVE=local \
SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/antflow?stringtype=unspecified' \
SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=<密码> \
MINIO_ENDPOINT=http://localhost:9000 MINIO_ACCESS_KEY=<key> MINIO_SECRET_KEY=<secret> \
mvn -B spring-boot:run

# 桌面端（默认 8000，/api/* 代理到 8080，/mobile/* 代理到 5173）
npm --prefix frontend ci --no-audit --no-fund && npm --prefix frontend start

# 移动端（默认 5173）
npm --prefix mobile ci --no-audit --no-fund && npm --prefix mobile run dev
```

`local` profile **不再提供任何默认密码**：库中存在待初始化的种子账号（`admin`、`bob`）时，必须配置 `ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD`（可选 `ANTFLOW_BOOTSTRAP_BOB_PASSWORD`）才能启动，否则应用会 fail-closed 拒绝启动；未配置口令的账号会停留在「待初始化」状态，既无法登录也无法改密（后端启动日志会点名提示）。

---

## 六、快速上手示例

### 6.1 桌面端：五步跑通一条审批流

1. 用 `admin` 登录 http://127.0.0.1:7070/ 。
2. 进入**表单设计**，从字段面板拖入文本框、人员选择等字段，保存并发布。
3. 进入**流程设计**，配置审批节点（可加条件分支、并行网关），发布前系统会做静态校验。
4. 在移动端或桌面端**发起**该流程，填写并提交。
5. 审批人在**待我处理**中打开任务，执行同意 / 驳回 / 转交 / 加签等操作，全过程可在**审批记录**与**流程监控**中查看。

### 6.2 接口冒烟（可直接复制执行）

```bash
# 1) 健康检查，期望 {"status":"UP"}
curl -s http://127.0.0.1:7070/actuator/health

# 2) 登录换取 JWT（200 且返回 accessToken）
TOKEN=$(curl -s -X POST http://127.0.0.1:7070/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<初始管理员密码>"}' \
  | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

# 3) 带鉴权查询待办（200，返回 records/total/page/size）
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://127.0.0.1:7070/api/tasks?view=pending&page=1&size=20'

# 4) 不带 token 请求同一接口，期望 401
curl -s -o /dev/null -w '%{http_code}\n' \
  'http://127.0.0.1:7070/api/tasks?view=pending&page=1&size=20'
```

分页接口统一响应结构：

```json
{ "records": [], "total": 0, "page": 1, "size": 20 }
```

权限过滤在 SQL 分页之前完成：`scope=mine` 只返回本人发起且不在待修改阶段的实例；`scope=authorized` 按管理员、任务参与、抄送、表单授权与数据范围返回可见实例。

### 6.3 移动端

浏览器打开 http://127.0.0.1:7070/mobile/login （或手机扫码访问同一地址）；与桌面端同域部署在 `/mobile/`，提供工作台、动态表单、草稿、任务审批、流程详情与待修改重提，在企业微信内打开时自动切换到 JS-SDK 的选图、拍照、录音、扫码与定位能力。架构、路由与工程约束见 [`AGENTS.md`](AGENTS.md) §6。

---

## 七、配置说明

配置优先级遵循 Spring Boot 约定：**环境变量 > `application-{profile}.yml` > `application.yml`**。容器环境统一通过 `SPRING_PROFILES_ACTIVE=prod` 启动。

### 7.1 `.env.docker.local`：Compose 必填项

| 变量 | 是否必填 | 说明 |
| --- | --- | --- |
| `POSTGRES_PASSWORD` | 必填 | PostgreSQL 密码；缺失时 Compose 直接报错退出 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | 必填 | MinIO 根凭据，同时作为后端附件与审计归档的访问密钥 |
| `JWT_SECRET` | 必填 | JWT 签名密钥，**至少 32 位随机字符** |
| `ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD` | 必填 | 首次初始化管理员凭据，UTF-8 8–72 字节；不覆盖后续改密 |
| `AUDIT_ARCHIVE_ENCRYPTION_SECRET` | 必填 | 审计归档加密密钥 |
| `ANTFLOW_INTEGRATION_ENCRYPTION_KEY` | 必填 | 企业微信等集成密钥的加密密钥 |
| `BACKUP_ENCRYPTION_SECRET` | 必填 | 系统备份加密密钥，至少 32 位随机字符 |
| `ANTFLOW_PUBLIC_BASE_URL` | 建议 | 对外访问地址，用于生成链接（默认 `http://127.0.0.1:7070`） |
| `ANTFLOW_OIDC_ALLOWED_HOSTS` | 可选 | 允许的 OIDC 提供方域名白名单，逗号分隔 |
| `ANTFLOW_OIDC_HTTPS_ONLY` | 可选 | 是否强制 OIDC 走 HTTPS，默认 `true` |

### 7.2 常用可选参数

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_POOL_MAX_SIZE` | `10` | 数据源连接池上限，公式与建议值见 `docs/capacity-tuning-2026-09-30.md` |
| `BACKUP_DIRECTORY` | `/www/antflow/backups` | 宿主机备份目录（容器内挂载为 `/backups`） |
| `CORS_ALLOWED_ORIGIN_PATTERNS` | `http://localhost:*,http://127.0.0.1:*,http://[::1]:*` | 允许的跨域来源模式 |
| `SCHEDULING_POOL_SIZE` | `4` | 后端 `@Scheduled` 调度线程池大小（outbox 轮询、媒体回收、企微同步等共用） |
| `AUDIT_TRUSTED_PROXIES` | `172.16.0.0/12` | 可信反向代理网段，用于取真实客户端 IP |
| `ANTFLOW_TIME_ZONE` | `Asia/Shanghai` | 定时与超时计算时区 |
| `ANTFLOW_WEBHOOK_ALLOWED_HOSTS` | 空 | Webhook 允许的目标域名白名单（SSRF 防护） |
| `ANTFLOW_WEBHOOK_HTTPS_ONLY` | `false` | Webhook 是否强制 HTTPS |
| `ANTFLOW_WEBHOOK_ALLOW_PRIVATE` | `false` | 是否允许 Webhook 访问内网地址 |
| `MOBILE_FILE_MAX_BYTES` | `104857600`（100 MB） | 移动端单文件上限 |
| `MOBILE_FILE_IMAGE_WATERMARK_CONCURRENCY` | `2` | 图片水印同步转码并发数 |
| `FFMPEG_BIN` / `WATERMARK_FONT` | `ffmpeg` / 自动探测 | 媒体处理可执行文件与中文水印字体路径 |

### 7.3 后端关键配置（`application.yml` → `antflow.*`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `antflow.jwt.ttl-seconds` | `86400` | JWT 有效期（1 天） |
| `antflow.auth.session-ttl-seconds` | `2592000` | 数据库会话有效期（30 天） |
| `antflow.login.per-minute` / `per-hour` | `5` / `30` | 登录限流阈值，触发后返回 429 |
| `antflow.auth.oidc-allowed-hosts` | 空 | OIDC 提供方域名白名单 |
| `antflow.audit.online-retention-days` | `365` | 在线审计事件保留天数 |
| `antflow.audit.archive-cron` | `0 20 2 * * *` | 审计归档任务执行时间（每日 02:20） |
| `antflow.mobile.files.storage` | `minio` | 附件存储实现 |
| `antflow.media.ffmpeg-bin` | `ffmpeg` | 媒体处理（含视频时长实测、水印）依赖 |
| `antflow.automation.max-attempts` | `8` | 触发任务失败重试上限 |
| `antflow.automation.zone-id` | `Asia/Shanghai` | 延时/定时任务时区 |
| `antflow.backup.directory` | `/backups` | 容器内备份目录 |
| `server.port` | `8080` | 后端监听端口（容器内） |

> **不要提交任何密钥**。本项目所有密钥只通过环境变量或管理页配置，仓库内不存放生产凭据。

---

## 八、目录结构

```text
ant-flow/
├── backend/                    # Spring Boot 后端、Flyway 迁移、测试与压测脚本
│   ├── src/main/java/          # 业务代码（auth、authz、form、workflow、org、audit、attachment…）
│   ├── src/main/resources/
│   │   └── db/migration/       # Flyway 迁移（只前滚，当前至 V58）
│   ├── backup/                 # 备份与恢复脚本（restore-backup.sh）
│   └── perf/                   # 写路径压测脚本
├── frontend/                   # Umi Max 桌面管理端（Ant Design Pro）
├── mobile/                     # Vite 移动审批端，部署基路径 /mobile/
├── shared/                     # 两端共享契约（api.generated.d.ts、form-validation.ts）
├── infra/                      # Nginx 配置与运维 SQL
├── docs/                       # 设计、验收、发布与排障文档
├── scripts/                    # 隔离测试栈、健康指标等运维脚本
├── _preview/                   # 移动端设计预览总目录（index.html 可本地打开）
├── compose.yaml                # 本地一体化运行栈（postgres + minio + backend + web）
├── compose.test.yaml           # 隔离测试栈
├── compose.write-load.yaml     # 写路径压测栈
├── Dockerfile.local            # 本地产物镜像（只复制产物，不在容器内构建）
├── deploy.sh                   # Linux 增量部署脚本
└── README.md
```

---

## 九、常见问题（FAQ）

**Q1：必须用 Docker 吗？**
不必须。可以走[源码开发模式](#53-方式三源码开发模式)，但需要自备 PostgreSQL 17 与 MinIO。用 Docker 的好处是依赖、迁移、附件存储、Nginx 路由与备份目录都已编排好。

**Q2：数据存在哪里？怎么备份和恢复？**
PostgreSQL 与 MinIO 各用一个命名卷（`antflow-local_postgres_data`、`antflow-local_minio_data`）。系统设置中的“系统备份”每天 02:30 生成 PostgreSQL、附件与审计归档的加密备份，保留 30 天，文件落在宿主机 `backups/` 目录。恢复必须停机执行：

```bash
BACKUP_ENCRYPTION_SECRET='与备份时相同的密钥' \
  backend/backup/restore-backup.sh backups/antflow-YYYYMMDD-HHMMSS.afbackup --confirm
```

**Q3：怎么改对外端口？**
改 `compose.yaml` 中 web 服务的端口映射（`"7070:80"`），并同步调整 `ANTFLOW_PUBLIC_BASE_URL`，然后 `docker compose --env-file .env.docker.local up -d`。

**Q4：管理员密码忘了 / 首次登录不进去？**
`ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD` 只在凭据未初始化时生效，之后改密不会被它覆盖。若确实无法登录，可通过数据库重置该账号的待设置凭据后重新用 bootstrap 密码初始化（生产环境请走运维窗口）。

**Q5：支持多租户吗？**
不支持。当前是单租户边界，内部分权靠「公司 / 部门 / 角色 + 数据范围」。见[十二、已知边界](#十二已知边界)。

**Q6：为什么非管理员看不到已发布的表单？**
这是设计使然。表单使用对非全局管理员有三重门槛：运行时能力 + **公司使用门槛** + USER/ROLE/DEPARTMENT 任一主体授权；只有全局管理员能修改公司门槛。存量表单由迁移补齐，新公司与新表单默认未获授权，需要管理员显式授权。

**Q7：有接口文档吗？**
有，由 springdoc 提供。**源码模式**直连后端访问 http://localhost:8080/swagger-ui ；**Docker 一体化部署**下网关只反代了 `/api/`、`/mobile/` 与 `/actuator/health`，未暴露文档路径，需要时自行在 `infra/docker/nginx.conf` 增加反代。另外，两端的前端契约由同一份后端 OpenAPI 快照生成，移动端只引用 `shared/api.generated.d.ts`，CI 会校验契约与提交内容逐字一致。

**Q8：为什么摄像头、麦克风、定位在企业环境不可用？**
浏览器要求这些能力在 **HTTPS** 下才可用（`localhost` / `127.0.0.1` 本地调试除外），并且需要用户授权。生产部署请配置 HTTPS 证书并在 Nginx 上启用。

**Q9：企业微信集成需要什么前提？**
需要一个**启用的内部自建应用**，以及公网 HTTPS 域名与可信域名配置、企业测试凭证。企业微信真实验收依赖这些外部条件，密钥只通过环境变量或管理页配置。

**Q10：能对接已有的 OIDC 登录吗？**
可以。支持标准 Authorization Code + PKCE。外部身份**只会绑定到已有且启用的本地用户**，不会自动创建账号，也不继承外部角色授权。

**Q11：前端改了代码，容器里不生效？**
镜像内不构建前端，需要重新构建产物再重建镜像（见[5.1 第 3、4 步](#51-方式一docker-一体化推荐)）。`deploy.sh` 会自动识别变化模块。

**Q12：日志在哪看？**
`docker compose --env-file .env.docker.local logs -f backend`。健康状态走 `/actuator/health`；业务审计与流程消息重放在桌面端「审计」与「流程运行监控」页面查看。

---

## 十、避坑指南

### 10.1 构建与部署

| 典型错误 | 原因 | 解决方式 |
| --- | --- | --- |
| `spring-boot-maven-plugin:repackage ... Unable to rename '...jar' to '...jar.original'` | 打包时该 JAR 被其他进程占用。Windows 上常见于：遗留的 `java -jar antflow-backend-...jar` 实例、杀毒软件正在扫描、IDE 索引 | 先找占用者再重跑打包：Windows 用 `Get-CimInstance Win32_Process -Filter "Name='java.exe'"`，Linux 用 `lsof backend/target/*.jar`。**注意：repackage 失败会把 JAR 降级成约 1 MB 的精简包，必须重新完整打包**，成功后应为约 105 MB |
| 前端构建报 `[safe-delete] SAFE_DELETE_BULK_CONFIRM_REQUIRED` 之类的删除保护拦截 | 部分开发环境（含 AI 编码工具的安全钩子）会拦截单次超过阈值的批量删除，而 umi/vite 构建时会清理 `.umi-production`、`dist` 等生成目录 | 不要关闭安全保护，改为**先把生成目录移出仓库**（`frontend/src/.umi-production`、`frontend/src/.umi`、`frontend/dist`、`mobile/dist`），让构建自行重建 |
| web 镜像构建在最后一步 `grep -q "antflow-csrf"` 失败 | 前端产物没有使用正确的 CSRF Cookie 名构建（例如沿用了测试环境产物） | 用 `ANTFLOW_AUTH_CSRF_COOKIE_NAME=antflow-csrf`（桌面）与 `VITE_AUTH_CSRF_COOKIE_NAME=antflow-csrf`（移动）重新 `npm run build`，再构建镜像 |
| `required variable POSTGRES_PASSWORD is missing a value` | 仓库根目录缺少 `.env.docker.local`，或启动时没带 `--env-file` | 按[7.1](#71-envdockerlocalcompose-必填项)补齐变量；改过该文件后请用 `deploy.sh --all` 或 `up -d --build` 重建 |
| `Node.js 22 or newer is required` | Node 版本过低（`deploy.sh` 自带校验） | 升级到 Node 22+；或用镜像内的构建链路 |
| `docker compose` 卡在 `Created` 不启动 | 历史上出现过 Compose 客户端请求停滞 | `docker compose ps -a` 确认后用 `docker start <容器名>` 单独启动；必要时结束卡住的客户端进程 |
| Git Bash（Windows）里 `docker exec ... sha256sum /app/app.jar` 报 `No such file or directory` | MSYS 把容器内绝对路径改写成了宿主机路径 | 加 `MSYS_NO_PATHCONV=1`，或用双斜杠 `//app/app.jar` |
| 自定义 JDBC URL 后启动报参数类型/类型转换异常 | 连接串缺少 `stringtype=unspecified` | URL 必须写成 `jdbc:postgresql://host:5432/antflow?stringtype=unspecified` |

### 10.2 运行时与数据

| 典型错误 | 原因 | 解决方式 |
| --- | --- | --- |
| 执行 `docker compose down -v` 后数据全没了 | `-v` 会连带删除 PostgreSQL 与 MinIO 的全部命名卷 | **不要加 `-v`**。需要恢复时用 `backend/backup/restore-backup.sh`（必须停机执行） |
| 开了水印后上传图片/视频返回 422 | 运行环境缺少 `ffmpeg` 或中文字体，图片水印是写进 MinIO 之前的同步步骤，缺工具直接拒绝 | 使用仓库提供的后端镜像（已内置 `ffmpeg` 与 `fonts-wqy-microhei`）；自建环境请安装并配置 `FFMPEG_BIN`、`WATERMARK_FONT` |
| 在手机浏览器打不开摄像头/录音/定位 | 非 HTTPS 环境下浏览器禁用这些能力 | 配置 HTTPS；本地调试用 `localhost` / `127.0.0.1` |
| 登录接口返回 429 | 触发登录限流（默认每分钟 5 次、每小时 30 次） | 等待窗口恢复；压测或联调时按需调整 `antflow.login.per-minute` / `per-hour` |
| 修改了历史 Flyway 迁移文件后启动失败 | 迁移一旦执行即冻结，只允许前滚 | 恢复历史文件，新增 `V59__xxx.sql` 等新迁移；`flyway_schema_history` 中有校验和，改历史文件必然校验失败 |
| 非管理员登录后看到的表单/实例很少 | 三层鉴权 + 公司使用门槛 + 主体授权的预期表现，不是缺陷 | 由全局管理员为该角色补齐公司门槛与 USER/ROLE/DEPARTMENT 授权 |
| 账号登不进去 / 改密报「原密码不正确」 | 账号可能停留在「待初始化」状态（`password_hash` 为哨兵值，见 `t_user`）：`admin` 或 `bob` 未配置对应的 `ANTFLOW_BOOTSTRAP_*_PASSWORD` 时就是这种状态，改密接口也会因原密码校验失败而 422 | 配好 `ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD` / `ANTFLOW_BOOTSTRAP_BOB_PASSWORD` 后重启（后端启动日志会点名未初始化的账号）；或由管理员在「系统管理 → 用户管理」中重置该账号密码。另：`ant.design` 是 V48 之前的旧公开口令，**已作废**，不要再用 |
| 备份文件“生成了但恢复失败” | 恢复所需的 `BACKUP_ENCRYPTION_SECRET` 与备份时不一致 | 妥善保存密钥；恢复命令必须传入与备份时相同的 `BACKUP_ENCRYPTION_SECRET` |

### 10.3 部署后再验证一次（推荐固化到发布流程）

```bash
# 容器状态：4 个常驻服务 Up，backup-init Exited (0)
docker compose --env-file .env.docker.local ps -a

# 健康检查：{"status":"UP"}
curl -s http://127.0.0.1:7070/actuator/health

# 入口可达：桌面与移动均应返回 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:7070/
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:7070/mobile/login

# 迁移状态：应看到 "Successfully validated N migrations" 与 "No migration necessary"
docker compose --env-file .env.docker.local logs backend | grep -E "Flyway|Migrate"

# 错误面：ERROR 行数应为 0
docker compose --env-file .env.docker.local logs backend | grep -c " ERROR "
```

回滚只需把旧镜像指回 `latest` 再重建容器（构建前请先给旧镜像打标）：

```bash
docker tag antflow-local-backend:<旧标签> antflow-local-backend:latest
docker tag antflow-local-web:<旧标签>     antflow-local-web:latest
docker compose --env-file .env.docker.local up -d --force-recreate backend web
```

---

## 十一、CI 与质量门禁

CI（`.github/workflows/ci.yml`）在 `push` / `pull_request` 到 `master`、`main` 时跑四个 job：`metrics`（六个健康指标，只禁止变差）、`backend`（含 ffmpeg 真机水印用例与真实 PostgreSQL）、`frontend`（Biome lint、类型检查、导入边界、契约一致性、测试、构建）、`mobile`（lint、测试、构建、bundle 预算）。**后端测试、桌面端类型检查/测试/构建、移动端全部检查为阻断门禁。**

本地开发命令（后端测试、桌面端 lint / tsc、移动端 `check:enterprise` 与 E2E、视觉基线刷新、容器编译）见 [`AGENTS.md`](AGENTS.md) §4。

| 范围 | 命令 |
| --- | --- |
| 隔离测试栈 | `bash scripts/test-docker.sh up` 起栈（对正式库做只读逻辑导出、复制 MinIO 对象到 `antflow-test_*` 卷，默认入口 http://127.0.0.1:17070，不占用正式环境的端口与卷）；测试栈默认保留，不再需要时用 `bash scripts/test-docker.sh destroy` 销毁 |
| 写路径压测 | `./backend/perf/run-write-load.ps1 -Quick` |

---

## 十二、已知边界

- 当前仍是**单租户**业务边界，没有完整的跨租户隔离模型。
- OIDC 只支持标准 Authorization Code + PKCE；不自动创建本地用户，也不接收外部角色授权。
- 企业微信仅支持一个启用的内部自建应用 Corp 免登入口；不提供桌面端企业微信扫码登录，也未实现服务商代开发授权。
- 企业微信真实验收依赖公网 HTTPS 域名、可信域名配置与企业测试凭证。
- 浏览器摄像头、麦克风与定位在生产环境依赖 HTTPS。
- 移动端不提供表单/流程设计器和组织管理。
- 超时计算尚未接入节假日工作日历。
- 核心审批流程不依赖 PWA 安装、Service Worker 或 Web Push。

---

## 十三、相关文档

- [项目上下文与架构事实](docs/PROJECT.md)
- [关键决策记录](docs/DECISIONS.md)
- [仓库 Agent 协作说明（开发约定、不变量与自检）](AGENTS.md)
- [本机发布与验收记录](docs/local-release-architecture-2026-10-15.md)
- [部署 runbook（含报错排查与验证清单）](docs/deploy-master-runbook-2026-10-17.md)
- [移动端运行说明](mobile/README.md)
- [移动端企业级验收记录](docs/mobile-enterprise-verification.md)
- [容量与连接池调优](docs/capacity-tuning-2026-09-30.md)
- [鉴权读路径性能报告](docs/performance-load-test-2026-08-24.md)

---

## 十四、待补充信息

以下内容需要项目维护者确认后补齐，本 README 中已用 **待补充** 标注对应位置：

| 项 | 说明 |
| --- | --- |
| 界面截图 | 见[三、界面预览](#三界面预览)：把真实界面截图放入 `docs/images/` 并放开 README 中的图片链接 |
| 在线演示地址 | 可访问的 Demo 或录屏 GIF（若有） |
| 项目简介补充 | 项目发起背景、目标用户规模、当前使用情况（如有对外可披露的信息） |
| 维护者与联系方式 | 维护团队、Issue 模板、讨论渠道（企业微信群 / 邮箱等） |
| 贡献指南 | 是否需要 `CONTRIBUTING.md`、分支模型、PR 规范 |
| Roadmap | 后续版本规划与已知中期目标 |
| 英文 README | 若需对外推广，可补 `README.en.md` |
| 分发方式 | 是否发布公共镜像到镜像仓库，供不构建直接拉取 |

---

## License

[Apache License 2.0](LICENSE)
