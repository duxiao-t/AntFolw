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
| Security smoke | 单测 + E2E mock + 有限 live 探测 | **PARTIAL** | 见第 4 节 |

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

### 4.2 Live 环境说明（2026-07-22）

- `http://127.0.0.1:8081/actuator/health` → UP
- `POST /api/auth/login`（admin/bob）→ 200，返回 `accessToken`
- 运行中的后端进程对部分 `/api/mobile/**` 返回 `INTERNAL_ERROR`（与本 worktree 源码/迁移可能不一致），**不能**作为本分支移动端 API 的 live 验收依据
- 安全语义以 **backend unit + mobile e2e mock** 为准；完整 live 冒烟需：用本分支重新构建并启动 backend，应用全部 Flyway，再对 `/api/public/branding`、admin 品牌写、refresh 旋转、上传与幂等做 HTTP 探测

## 5. Task 15 为通过门禁做的最小修复

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
