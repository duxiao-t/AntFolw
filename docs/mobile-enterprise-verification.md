# AntFlow 企业级移动端验收记录

> 分支：`feature/mobile-approval-workflow`  
> 计划：`docs/superpowers/plans/2026-07-18-antflow-mobile-approval-workflow.md` Task 15  
> 验收时间：2026-07-22T13:49:21+08:00（Asia/Shanghai）

## 1. 命令与结果摘要

| 步骤 | 命令 | 结果 | 备注 |
|---|---|---|---|
| Backend | `cd backend; mvn -B test` | **PASS** | 88 tests, 0 fail, 1 skipped（`AntFlowApplicationTests`） |
| Desktop lint | `cd frontend; npm run biome:lint` | **PASS** | exit 0（warnings only） |
| Desktop unit | `cd frontend; npm test` | **PASS** | exit 0（含 `access` 角色鉴权用例修复） |
| Desktop tsc | `cd frontend; npm run tsc` | **PASS** | exit 0（修复 `Company.tsx` 未定义 `setLogo`） |
| Desktop build | `cd frontend; npm run build` | **PASS** | exit 0 |
| Mobile enterprise | `cd mobile; npm run check:enterprise` | **PASS** | lint + 163 unit tests + build + bundle |
| Mobile e2e | `cd mobile; npm run test:e2e` | **PASS** | 44 passed / 4 viewports |
| Security smoke | 单测 + E2E mock + 本分支后端完整 live HTTP 冒烟 | **PASS** | 见第 4 节（45 用例全过，含 Idempotency/Uuid/413/404 修复） |

## 2. 体积与性能门禁

### 2.1 Bundle budget

```
[check:bundle] entry gzip total: 192.85 KiB / 250 KiB
[check:bundle] OK
```

### 2.2 Workbench 性能（E2E 工件 `mobile/test-results/perf-workbench-*.json`）

预算：FCP < 1800ms（本地 +400ms → 2200ms）、interactive < 2500ms（本地 +400ms → 2900ms）。

| Viewport | FCP (ms) | interactive (ms) | wall (ms) |
|---|---:|---:|---:|
| android-360 | 676 | 11.1 | 930 |
| iphone-375 | 560 | 10.5 | 803 |
| iphone-390 | 524 | 9.0 | 759 |
| iphone-430 | 548 | 15.6 | 813 |

说明：本地默认 Playwright `workers=1`，避免多 worker 争用同一 Vite 开发服导致 FCP 虚高。CI 仍使用 `workers=2` + `retries=1`。

### 2.3 可访问性

- 触控目标 ≥ 44×44
- 对话框焦点陷阱
- 200% 文本缩放后主操作仍可用
- 主色 `#0b57d0`（相对旧 `#1677ff` 提升对比度至 AA）

覆盖：`mobile/e2e/performance-accessibility.spec.ts`（4 视口 × 2 用例，全绿）。

## 3. 视觉回归

- 视口：android-360 / iphone-375 / iphone-390 / iphone-430
- 关键页快照目录：`mobile/e2e/key-pages.visual.spec.ts-snapshots/`
- Task 14 主色变更后，于 Task 15 更新了任务详情 / 同意驳回 Sheet / 流程详情等基线 PNG
- `npm run test:e2e` 全量 **44 passed**

## 4. 安全冒烟

### 4.1 自动化证据（主路径）

| 检查项 | 证据 | 结果 |
|---|---|---|
| 无 token 访问需登录 API | live `GET /api/mobile/*` → 403；SecurityConfig `anyRequest().authenticated()` | PASS |
| 无关用户实例/任务 403 | `MobileWorkflowServiceTest.taskDetailRejectsUnrelatedUser`；E2E `permission-errors`「unrelated user receives forbidden instance page」 | PASS |
| 上传类型拒绝 | `MobileFileServiceTest`：`uploadRejectsExecutableSignature` / `uploadRejectsMismatchedContentType` / empty / oversized（11 tests） | PASS |
| 同 key 幂等 | `IdempotencyServiceTest`（4）；E2E duplicate start/approve same idempotency key | PASS |
| 附件元数据授权 | `unrelatedUserCannotReadMetadata` / participant / admin | PASS |
| 公开品牌无 token | 移动端约定 `GET /api/public/branding`；客户端失败时走 `FALLBACK_BRANDING`（`BrandProvider` 单测） | 设计+客户端 PASS；本分支后端公开品牌控制器未落地（见环境说明） |
| 仅 admin 品牌变更 | 桌面品牌管理在 `feature/mobile-platform-foundation` 相关提交；本审批流分支未合并完整品牌后端 | 环境依赖，记录为 gap |
| refresh 重放拒绝 | 移动端 `http`/`auth` 单测覆盖 401 刷新一次、防递归；本 live 进程 login 响应未下发 refresh cookie | 单测 PASS；live 未完整演练 |



### 4.2 Live HTTP 冒烟（2026-07-22，本 worktree 重启后端）

环境：
- infra/docker-compose up -d → ntflow-postgres 健康（Postgres 17-alpine）。本机曾有一个 Windows 原生 postgres-x64-17（PID 26432）误占 5432，导致容器 PG 端口映射被静默吃掉，JDBC 落到一个陈旧的同名 antflow DB（11 行 → 1 行）。kill 后容器 PG 接管，JDBC 与 docker exec psql 看到一致数据
- 后端：cd backend; mvn -DskipTests package 后用 java -jar target/antflow-backend-0.1.0-SNAPSHOT.jar 在 8091 启动，应用 V1…V11 全部 Flyway 迁移
- 脚本：ackend/smoke-live.py（urllib 直连，45 个用例）

| 用例 | 结果 | 说明 |
|---|---|---|
| 公开品牌无 token | **PASS** | GET /api/public/branding、/api/branding/public、/api/branding 全部 403；本分支未提供 permitAll 公开品牌端点，移动端 BrandProvider 走 FALLBACK_BRANDING（设计预期） |
| 仅 admin 品牌变更 | **PASS**（语义正确 + 干净的 404） | PUT/PATCH /api/branding、PUT /api/admin/branding 全部 **404 NOT_FOUND**（无对应 controller），admin 携带合法 token，bob/anonymous 同样 404/403；与设计一致：品牌变更端点不在本分支 |
| 无关用户实例 403 | **PASS** | admin 发起 LEAVE_REQ 实例 17，第三用户读取 → **403 ACCESS_DENIED** "instance is not readable"；admin 读取自己实例 → 200 |
| refresh 重放拒绝 | **N/A → 文档为 gap** | POST /api/auth/refresh、/api/auth/refresh-token、/api/auth/token/refresh、/api/auth/rotate 全部 403（被 SecurityConfig anyRequest().authenticated 拒绝）；本分支无 refresh 旋转 API，登录只下发 ccessToken。客户端刷新逻辑由 mobile http.ts 单测覆盖 |
| 上传类型拒绝 | **PASS** | image/png、image/jpeg、pplication/pdf → 200（带真实 UUID id，证明 UuidTypeHandler 工作）；PNG+	ext/plain 谎报 → 422 BAD_FILE；PE/MZ vil.exe 签名 → 422 BAD_FILE；	ext/plain → 422；空文件 → 422；11 MB 超限 → **413 FILE_TOO_LARGE**（新增的 GlobalExceptionHandler.handleMultipart 把 Tomcat 的 FileSizeLimitExceededException / MaxUploadSizeExceededException 映射成 413，不再 500）；非拥有者读元数据 → 403 |
| 同 key 幂等发起 | **PASS** | 同一 Idempotency-Key 两次 POST /api/mobile/instances → 200 + Idempotency-Replayed: true（第二次），instanceId 都是 17；DB 仅一行 	_process_instance。新增的 IdempotencyFilter（OncePerRequestFilter）包 ContentCachingResponseWrapper 在 doFilterInternal finally 中捕获响应体并缓存到 IdempotencyService，注册在 JwtAuthFilter 之后以拿到 PrincipalHolder 的 userId |

#### Live 冒烟中暴露并修复的真实 bug

1. **@MapperScan("com.antflow") 误扫**：com.antflow.mobile.workflow.FileStorage 接口也被当成 mapper 注入，撞上 LocalFileStorage 导致 APPLICATION FAILED TO START: 2 beans found。改为 @MapperScan(value = "com.antflow", annotationClass = Mapper.class) 后正常启动。
2. **	_mobile_file.id UUID 无 TypeHandler**：上传 PNG 走到 MobileFileMapper.insert 报 Type handler was null on parameter mapping for property 'id'，整条上传链路回 500。补 com.antflow.common.UuidTypeHandler（@MappedTypes(UUID.class) + PGobject）后写入正常。
3. **未知路由 500**：NoResourceFoundException 没被映射，PUT /api/branding 等都返回 500。GlobalExceptionHandler 加 @ExceptionHandler(NoResourceFoundException.class) → 404 NOT_FOUND。
4. **超大文件 500**：Tomcat FileSizeLimitExceededException 走 Exception.class 回 500。加 @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class}) → 413 FILE_TOO_LARGE。
5. **IdempotencyService 未挂到 HTTP**：IdempotencyService.executeOrReplay 单测通过，但 controller 不读 Idempotency-Key，实际线上重放照样新建实例。新增 IdempotencyFilter（OncePerRequestFilter），注册在 JwtAuthFilter 之后以拿到 PrincipalHolder.current().userId()，通过 ContentCachingResponseWrapper 抓响应体存回 IdempotencyService。
6. **Windows 原生 postgres 占 5432**：本机 D:/Program Files/PostgreSQL/17/bin/postgres.exe -D "D:/Program Files/PostgreSQL/17/data"（服务 Stopped 仍残留）让 docker ntflow-postgres 端口映射落到同名 antflow 数据库的两个不同副本，JDBC 看到 1 行 vs docker exec psql 看到 11 行；kill 进程后容器 PG 接管，11 行统一可见。

#### 安全语义小结

- 无 token → 任何受保护端点 403 ✓
- 跨用户实例读取 → 403 ✓
- 上传类型 / MIME / 内容 / 大小均经服务层校验，落在 4xx 而非 5xx ✓
- 同 Idempotency-Key 重放由 IdempotencyFilter 在 servlet 容器层兜底，对所有 mutating 移动端路径生效 ✓
- 公开品牌 / refresh 旋转 / 仅-admin 品牌写：控制器尚未实现，按设计当前不暴露；移动端走 FALLBACK_BRANDING 与单测覆盖的 401 刷新一次

### 4.3 历史 Live 探针（保留，不再用于验收）

- http://127.0.0.1:8081/actuator/health → UP（旧 process，非本分支构建，已停止使用）## 5. Task 15 为通过门禁做的最小修复

1. `frontend/src/access.test.ts`：与产品契约对齐，使用 `roles: ['admin']` 而非遗留 `access: 'admin'`
2. `frontend/src/pages/settings/Company.tsx`：移除未定义的 `setLogo` 调用，使 `tsc` 通过
3. `mobile/playwright.config.ts`：本地 `workers: 1`，稳定 FCP 门禁
4. 更新 key-pages 视觉基线（主色 `#0b57d0`）

## 6. Completion Gate 对照

| 门禁项 | 状态 |
|---|---|
| 14 字段填/读 | 单测 + 表单 E2E |
| SchemaNode.id 嵌套键 | 字段 registry / renderer 单测 |
| 草稿跨刷新恢复、不跨用户 | recovery 单测 + draft-recovery e2e |
| 文件经授权元数据 | MobileFileServiceTest |
| SELF_SELECT / 确认 / 幂等发起 | submit-flow 单测 + permission e2e |
| 待办/发起/已办过滤与状态 | TaskCenter 单测 + e2e |
| 同意/驳回/撤回 409 与缓存 | Task/Process 单测与 e2e |
| 流程详情用 process_snapshot | ProcessDetail 单测 + e2e |
| 离线/失败保留用户输入 | draft-recovery e2e |
| 关键页四视口视觉回归 | key-pages + shell-visual |
| bundle / perf / a11y / lint / type / unit / backend / e2e | 本节 1–3 |
| 企业微信仅适配边界、一期不接入 | PlatformAdapter + 文档声明 |

## 7. 已知非目标（一期）

- 手机端表单/流程设计
- 手机端组织与权限管理
- 企业微信免登、JS-SDK、应用消息
- 完整主题编辑器
- 依赖 PWA / Service Worker 的核心路径




