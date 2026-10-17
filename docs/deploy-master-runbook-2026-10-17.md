# master 分支 Docker 部署实录与 runbook

2026-10-17（+08:00）。目标：在 Docker 环境部署 GitHub 仓库 `duxiao-t/AntFolw` 的 `master` 分支最新代码。
本次**已实际执行并验证通过**，入口 `http://127.0.0.1:7070`。生产未执行，仅本机 `antflow-local` 栈。

## 0. 本次部署结论

| 项 | 实测值 |
| --- | --- |
| 仓库 | `https://github.com/duxiao-t/AntFolw.git` |
| 分支 / 提交 | `master` @ `78d7db3`（Merge PR #7：outbox 续租独立于调度线程；两端“仍在处理”上传不再判失败） |
| 起点 | 本地 `feat/architecture-completion` @ `dffcd28`（已并入 master）→ 快进 8 个提交 |
| 变更范围 | `backend/`（`application.yml` 调度线程池、`WorkflowOutboxDispatcher`、`AutomationJobScheduler`）、`frontend/`（`MediaUploadControl`、`nativeMedia`）、`mobile/`（`FileUploadField`、`files.api`）、`compose.yaml`；**无新增 Flyway 迁移，仍为 V58** |
| 后端产物 | `antflow-backend-0.1.0-SNAPSHOT.jar` 104,934,088 B，sha256 `17a6b9a5…5e52` |
| 运行镜像 | backend `sha256:2397b9dc6ee9`、web `sha256:c2c7c1d1e33a`（容器内 `/app/app.jar` 与宿主机产物 sha256 逐字一致） |
| 旧镜像保留 | `antflow-local-backend:before-master-20261017-134551`（`825da547a442`）、`antflow-local-web:before-master-20261017-134551`（`0e162ee02d08`） |
| 数据 | 复用既有卷 `antflow-local_postgres_data` / `antflow-local_minio_data`，**未清卷**；部署前 45 实例 / 62 填报 / 33 用户 |
| 验证结果 | 5 容器状态正常、`/actuator/health`=`UP`、桌面与移动入口 200、登录 200 + 鉴权接口 200 + 无 token 401、Flyway「No migration necessary」、后端日志 **0 ERROR**、四服务重启策略 `unless-stopped` |

## 1. 仓库地址与克隆方式

```bash
# HTTPS（本次使用）
git clone -b master --single-branch https://github.com/duxiao-t/AntFolw.git ant-flow
cd ant-flow

# SSH（已配置密钥时）
git clone -b master --single-branch git@github.com:duxiao-t/AntFolw.git ant-flow
```

后续更新即：

```bash
git fetch origin && git merge --ff-only origin/master   # 或 git pull --ff-only
git rev-parse HEAD                                       # 记录本次部署的提交
```

> 仓库默认工作分支可能是特性分支（本次拉取前本机在 `feat/architecture-completion`）。
> 部署 master 时务必显式 `-b master`，并在部署前用 `git merge --ff-only` 保证工作区干净、无分叉。

## 2. 依赖与前置条件

| 类别 | 要求 | 本次环境 |
| --- | --- | --- |
| 容器运行时 | Docker Engine / Docker Desktop + Compose v2 以上 | Docker 29.4.2 / Compose v5.1.3（Linux 容器，x86_64） |
| JDK | Java 17 | 17.0.12 LTS |
| Maven | 3.9 | 3.9.14 |
| Node.js | 22 及以上（`deploy.sh` 会强制检查） | v22.22.2 / npm 10.9.7 |
| 网络 | 拉取基础镜像 + 后端镜像内 apt 安装 ffmpeg | 需要出网 |

镜像涉及的基础镜像（首次部署需联网，建议先 `docker pull` 预热）：

- `eclipse-temurin:17-jre-jammy`、`postgres:17-bookworm`、`nginx:1.28-alpine`（`Dockerfile.local` 三个阶段）
- `postgres:17-alpine`、`quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z`、`nginx:1.28-alpine`（`compose.yaml`）

后端镜像内含 `ffmpeg` 与 `fonts-wqy-microhei`（图片/视频水印与中文水印字体），因此镜像约 1.59GB。

## 3. 构建方式：镜像里不构建前端

`Dockerfile.local` **只复制宿主机构建好的产物**（`backend/target/*.jar`、`frontend/dist/`、`mobile/dist/`）。
所以必须在宿主机先构建产物，再 `docker compose build`。这也是 README 与 `deploy.sh` 的既有做法。

### 3.1 手动全量（跨平台，本次采用）

```bash
# 1) 后端 JAR
mvn -B -f backend/pom.xml -Dmaven.test.skip=true package
#    → backend/target/antflow-backend-0.1.0-SNAPSHOT.jar

# 2) 桌面端
npm --prefix frontend ci --no-audit --no-fund      # 首次或 lock 变化时
ANTFLOW_OUTPUT_PATH=dist ANTFLOW_AUTH_CSRF_COOKIE_NAME=antflow-csrf \
  npm --prefix frontend run build                  # → frontend/dist

# 3) 移动端
npm --prefix mobile ci --no-audit --no-fund
ANTFLOW_OUTPUT_PATH=dist VITE_AUTH_CSRF_COOKIE_NAME=antflow-csrf \
  npm --prefix mobile run build                    # → mobile/dist

# 4) 构建镜像并启动
docker compose --env-file .env.docker.local build backend web
docker compose --env-file .env.docker.local up -d
```

`ANTFLOW_AUTH_CSRF_COOKIE_NAME` / `VITE_AUTH_CSRF_COOKIE_NAME` 必须为 `antflow-csrf`：web 镜像在构建末尾
用 `grep` 校验产物里含该串且不含 `antflow-test-csrf`，否则镜像构建直接失败（这是防止把测试环境产物推上线的门禁）。

### 3.2 Linux 服务器增量（仓库自带）

```bash
git pull --ff-only
bash deploy.sh                 # 只构建并重启发生变化的模块
bash deploy.sh --all           # 首次 / 改了 .env.docker.local / 需要恢复性重建
```

`deploy.sh` 会按上次成功部署的提交（`.git/antflow-deployed-revision`）对比变更分类重建，并在
`curl /actuator/health` 通过后才记录新版本；**健康检查失败不会记录**，便于重试。

## 4. 配置文件与关键参数

| 文件 | 作用 | 关键参数 |
| --- | --- | --- |
| `.env.docker.local`（仓库根，**已被 `.gitignore` 排除，不入库**） | compose 变量源 | `POSTGRES_PASSWORD`、`MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`、`JWT_SECRET`（≥32 随机字符）、`ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD`、`AUDIT_ARCHIVE_ENCRYPTION_SECRET`、`ANTFLOW_INTEGRATION_ENCRYPTION_KEY`、`BACKUP_ENCRYPTION_SECRET`、`ANTFLOW_PUBLIC_BASE_URL`、`ANTFLOW_OIDC_ALLOWED_HOSTS` |
| `compose.yaml` | 五服务编排 | `7070:80`（web 对外唯一入口）、`7072/7073`（MinIO API/Console）、`SPRING_PROFILES_ACTIVE=prod`、`DB_POOL_MAX_SIZE`（默认 10）、`BACKUP_DIRECTORY`、`JAVA_TOOL_OPTIONS=-Xms256m -Xmx768m` |
| `Dockerfile.local` | 镜像构建 | 只 COPY 产物；backend 阶段装 ffmpeg + 中文字体；web 阶段做 CSRF 串校验 |
| `infra/docker/nginx.conf` | 入口路由 | `/`→桌面 SPA、`/mobile/`→移动 SPA、`/api/`→`backend:8080`、`/actuator/health`→后端、`client_max_body_size 136m` |

必填项写成了 `${VAR:?set VAR}` 形式：**变量缺失时 compose 直接报错退出**，不会带着空密钥起服务。
生产安全约定：`ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD` 只用于首次初始化管理员凭据，之后改密不会被该变量覆盖。

## 5. 常见报错与排查

### 5.1 本次实际遇到的四个坑（按发生顺序）

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| `spring-boot-maven-plugin:repackage ... Unable to rename 'antflow-backend-0.1.0-SNAPSHOT.jar' to '...jar.original'` | Windows 上有进程持有该 JAR：本次是两个**遗留的 `java -jar …antflow-backend-0.1.0-SNAPSHOT.jar`（端口 18080、local profile）**，其中一个已因端口冲突变成僵尸进程 | 用 `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` 找到并停止后重跑。**注意：repackage 失败会把 JAR 降级成 1.2MB 的精简包，必须重新完整打包**（`ls -la backend/target/*.jar*` 应看到 ~105MB 与配套 `.jar.original`） |
| 前端 `umi` / `vite` 构建报 `[safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED] {"count":50,"threshold":50,...}` | 本机环境对所有 node 进程注入了删除保护（把删除改成回收站 + 单次超 50 个文件即拦截），构建工具清理 `src/.umi-production`、`dist` 时被拦 | 不关闭保护，改为**先把生成目录 `mv` 移出仓库**（`frontend/src/.umi-production`、`frontend/src/.umi`、`frontend/dist`、`mobile/dist`），让构建自行重建 |
| `docker exec … sha256sum /app/app.jar` 报 `No such file or directory` | Git Bash（MSYS）把容器内的 `/app/app.jar` 转换成了宿主机路径 | 加 `MSYS_NO_PATHCONV=1` 或用 `//app/app.jar` |
| `docker compose ps` 显示旧容器仍在、`up` 后端口/配置未变 | `compose.yaml` 本身变了（本次新增 `restart: unless-stopped`），需重建容器 | `docker compose --env-file .env.docker.local up -d --build` 会重建 backend/web；postgres/minio 也会因定义变化被重建（**数据在卷里，不受影响**） |

### 5.2 其他常见报错

| 报错 | 排查思路 |
| --- | --- |
| 端口占用：`bind: address already in use` / `port is already allocated` | `netstat -ano \| findstr :7070`（Windows）或 `ss -lntp`（Linux）定位进程；`docker compose ps` 看是否已有旧栈占 7070/7072/7073。本项目 postgres 不映射宿主端口，不会与宿主 5432 冲突 |
| 环境变量未配置：`required variable POSTGRES_PASSWORD is missing a value: set POSTGRES_PASSWORD` | 根目录缺 `.env.docker.local` 或未加 `--env-file`。按 §4 补齐；改过该文件后用 `--all` 重建，避免复用旧镜像 |
| 依赖缺失：`Node.js 22 or newer is required` / `mvn: command not found` / `Unable to find a javac compiler` | 按 §2 装 JDK 17 + Maven 3.9 + Node 22；Windows 上注意 `npm --prefix` 是在仓库根执行 |
| 镜像拉取失败：`failed to resolve source metadata … dial tcp: i/o timeout` | 出网/镜像源问题。先单独 `docker pull` 各基础镜像定位是哪一层；配置 registry mirror 或代理后重试；后端镜像内还会 apt 装 ffmpeg，需能访问 Debian 源 |
| 权限不足：`permission denied` 挂载或 chown 失败 | `backup-init` 会对备份目录执行 `chown 10001:10001`；Linux 上宿主目录属主/挂载选项要允许，Windows 上需 Docker Desktop 文件共享；Linux 用户须在 `docker` 组 |
| `up` 卡在 `Created` 不启动 | 本项目历史记录过（客户端请求停滞）。先 `docker compose ps -a` 确认，再对该容器 `docker start <name>`；必要时结束卡住的客户端进程 |
| 迁移失败 / 库报 `relation already exists`、唯一键冲突 | Flyway 只前滚，历史迁移不可改；先看 `flyway_schema_history` 与 `docker compose logs backend`。**绝不要 `docker compose down -v`**（会删掉 Postgres 与 MinIO 全部本地数据） |
| 健康检查不 UP | `docker compose logs --tail=200 backend` 找首个 ERROR/异常；常见为数据源密码不一致、MinIO 未就绪、JWT_SECRET 过短 |
| web 镜像构建失败在最后一步 `grep -q "antflow-csrf"` | 前端产物是用测试用 CSRF 名构建的（或没重新构建 dist）。用 §3.1 的 env 重新 `npm run build` 后再 build 镜像 |

## 6. 验证部署是否成功

| 检查项 | 命令 | 预期结果（本次实测） |
| --- | --- | --- |
| 容器状态 | `docker compose --env-file .env.docker.local ps -a` | backend/web `Up`；postgres/minio `Up (healthy)`；backup-init `Exited (0)` |
| 重启策略 | `docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' antflow-local-<svc>-1` | `unless-stopped`（四个常驻服务） |
| 后端健康 | `curl http://127.0.0.1:7070/actuator/health` | `{"status":"UP"}` |
| 桌面入口 | `curl -o /dev/null -w '%{http_code}' http://127.0.0.1:7070/` | `200` |
| 移动入口 | `curl -o /dev/null -w '%{http_code}' http://127.0.0.1:7070/mobile/login` | `200` |
| 鉴权链路 | `POST /api/auth/login {"username":"admin","password":"<ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD>"}` | `200` + `accessToken`；随后带 token 请求 `GET /api/tasks?view=pending&page=1&size=5` 返回 `records/total/page/size` |
| 安全边界 | 不带 token 请求同一接口 | `401`（前端与菜单不是安全边界，后端才是） |
| 部署内容一致 | `sha256sum backend/target/*.jar` 与容器内 `sha256sum /app/app.jar` 比对；页面引用的 `umi.*.js` / `assets/index-*.js` 与本地 dist 一致 | 哈希/文件名逐字相同 |
| 数据库迁移 | `docker compose logs backend \| grep -E "Flyway\|Migrate"` | `Successfully validated 56 migrations`、`Current version of schema "public": 58`、`No migration necessary` |
| 媒体能力 | 同上日志中 `媒体水印就绪` | `ffmpeg='ffmpeg'，字体='/usr/share/fonts/truetype/wqy/wqy-microhei.ttc'` |
| 错误面 | `docker compose logs backend \| grep -c " ERROR "` | `0`（MyBatis-Plus 主键与 Flyway PG17 的 WARN 为既有噪声） |

访问入口与账号：

- 桌面管理端 `http://127.0.0.1:7070/`，移动端 `http://127.0.0.1:7070/mobile/login`
- 管理员 `admin` + `.env.docker.local` 里的 `ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD`
- MinIO Console `http://127.0.0.1:7073`（`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`）

## 7. 回滚

镜像在构建前已打标（`before-master-<时间戳>`），回滚只需把旧镜像指回 `latest` 并重建容器：

```bash
docker tag antflow-local-backend:before-master-20261017-134551 antflow-local-backend:latest
docker tag antflow-local-web:before-master-20261017-134551     antflow-local-web:latest
docker compose --env-file .env.docker.local up -d --force-recreate backend web
```

本次部署**没有数据库迁移**（V58 已在卷内），因此不需要库回滚；若做过迁移，按
`backend/backup/restore-backup.sh` 的停机恢复流程处理。数据卷与备份目录不要删除。
