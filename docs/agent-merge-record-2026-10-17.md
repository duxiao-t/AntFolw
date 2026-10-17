# Agent 协作文件合并记录（2026-10-17）

> 一次性历史档案：记录四份 agent 说明文件的合并取舍、命名变更与原文回溯方式。
> **后续协作规则以根 `AGENTS.md` 为准，本文件不随仓库演进更新。**

## 1. 合并范围与命名时间线

| 项 | 内容 |
| --- | --- |
| 基线提交 | `de982a0`（整合前的最后一次提交） |
| 被合并文件 | `AGENTS.md`（26 行，已入库）、`agent.md`（191 行，已入库）、`codex.md`（230 行，已入库）、`ant-design-pro-master/CLAUDE.md`（6083 字节，未入库） |
| 合并结果 | 根目录单一 `AGENTS.md`，原四份文件移除 |
| 时间线 | ① 2026-10-17 四份整合为根 `AGENT.md`（275 行，15 节）；② 同日为恢复 agent 工具自动发现能力改名为 `AGENTS.md`，正文未变；③ 同日按下述第 6 节做职责划分，收敛为 12 节 |
| 命名取舍 | `AGENTS.md` 是多数 agent 工具（Codex、Cursor、Copilot 等）默认自动发现的约定名；`AGENT.md` 仅为整合期的过渡名 |
| Windows 注意 | `agent.md` → `AGENT.md` 属**仅大小写变化**，在大小写不敏感的文件系统上需两步改名或"先删后建"；本次 `AGENT.md` → `AGENTS.md` 不是大小写变化，可直接 `git mv` |

## 2. 上游脚手架文件的取舍（原 `AGENTS.md` §13）

该文件描述的是仓库内**上游脚手架副本**（`ant-design-pro-master/`，已 .gitignore），其中
`utoopack` 构建、`/pro-upgrade` 与 `/antd` skill、Cloudflare Worker、mock 体系、8 语言 i18n、
`GET /api/currentUser` 鉴权、`src/services/ant-design-pro/` 自动生成目录等只对脚手架本身有效。
本次仅提取其中**同样适用于 AntFlow `frontend/` 的硬规则**（见 `AGENTS.md` §7）与**通用编码行为准则**（见 `AGENTS.md` §8），
其余标注为脚手架专用、未纳入。

## 3. 冲突与差异说明（原 `AGENTS.md` §14）

> 注：下表"各文件表述"列中的 `AGENTS.md` 指**合并前的 26 行旧版**（与当前根文件同名但不同物）；
> `AGENT.md` 指整合期用名，现名为 `AGENTS.md`。

| # | 条目 | 各文件表述 | 采用的版本 | 依据 |
| --- | --- | --- | --- | --- |
| 1 | Flyway 版本 | `codex.md`：V1–V43；根 `CLAUDE.md`（不在本次四份内，仅备注）：V1–V21 | **V58** | 实测 `backend/src/main/resources/db/migration/` 最高为 `V58__wecom_sync_lease.sql`；「只前滚」不变量保留 |
| 2 | 迁移冻结边界 | `codex.md`：V40–V42 已冻结、V43 前滚；`AGENTS.md`：已执行迁移只前滚 | 统一为「**已执行迁移只前滚，不改历史文件，新变更新增版本**」，不写死具体版本号 | 避免版本号再次过期，`AGENTS.md` 的表述更稳定 |
| 3 | 移动端 bundle 预算 | `codex.md`：≤250 KiB、入口 181.12 KiB（2026-07-31 基线） | **entry 160 KiB / lazy 180 KiB** | 实测 `mobile/scripts/check-bundle-budget.mjs` 常量 `ENTRY_BUDGET_KIB=160`、`LAZY_BUDGET_KIB=180` |
| 4 | 设计基线路径 | `agent.md`：`.superpowers/brainstorm/mobile-design-20260718/content/all-key-pages-gallery.html`；`codex.md`：`_preview/` 与 `docs/superpowers` | **`_preview/` + `docs/superpowers`** | `.superpowers/brainstorm/mobile-design-*` 实测**已不存在**；`_preview/` 已入库且页面齐全 |
| 5 | 预览页数量 | `agent.md`：18 页 + 1 离线态；`codex.md`：19 页 | **20 页 + `index.html` 总目录** | 实测 `_preview/*.html` 共 21 个（编号 01–13、15–21，缺 14） |
| 6 | 前端依赖安装 | `agent.md` / 根 `CLAUDE.md`：`npm install`；`codex.md`：`npm ci` | **`npm ci`** | 仓库有 `package-lock.json`，`ci` 才能与锁定版本一致（CI 亦用 `npm ci`） |
| 7 | 前端历史 tsc 错误 | 根 `CLAUDE.md`（不在四份内，仅备注）：存在历史 tsc 错误、构建不因类型错误失败 | **以当前 CI 为准：`npm run tsc` 是阻断门禁且近期全绿** | `.github/workflows/ci.yml` 的 frontend job 把 `npm run tsc` 列为必须通过的步骤 |
| 8 | 种子账号 | `codex.md` / 根 `CLAUDE.md`：`admin`、`bob` + `ant.design`；`AGENTS.md` / `README.md`：生产必须 `ANTFLOW_BOOTSTRAP_ADMIN_PASSWORD` | **两者并存，按 profile 区分**（非真冲突） | `local` 为待初始化账号设默认值；非 local 必须显式初始化 |
| 9 | 文件角色定位 | `agent.md` 自称「与 `codex.md` 互补的任务上下文」 | 合并后**不再保留双文件结构** | 移动端 UI 整改已完成，`agent.md` 的剩余价值是设计基线与工程约束，已并入 `AGENTS.md` §6 |
| 10 | 脚手架内容适用范围 | `ant-design-pro-master/CLAUDE.md` 通篇按脚手架自身描述 | **只提取适用于 `frontend/` 与通用的部分** | 该目录已 .gitignore、不参与 AntFlow 构建，其 `utoopack` / `/pro-upgrade` / Cloudflare Worker 等与本项目无关 |

## 4. 原文件处置与回溯

| 文件 | 合并前状态 | 处置 |
| --- | --- | --- |
| `AGENTS.md` | 26 行，已入库 | 内容被整合版取代（新文件沿用同名路径） |
| `agent.md` | 191 行，已入库 | 从仓库移除，内容并入根 `AGENTS.md` |
| `codex.md` | 230 行，已入库 | 从仓库移除，内容并入根 `AGENTS.md` |
| `ant-design-pro-master/CLAUDE.md` | 6083 字节，未入库（所在目录被 .gitignore） | 移入回收目录 `E:/code/_antflow-trash-20261017/agent-files-20261017/` |

回溯合并前的原文（固定基线 `de982a0`）：

```bash
git show de982a0:AGENTS.md
git show de982a0:agent.md
git show de982a0:codex.md
```

同批落地的提交：`f738587`（整合 + 改名 + `.gitignore` 忽略 `.workbuddy/`），以及紧随其后的
「划分 README 与 AGENTS 职责」提交（见 `git log --oneline`）。

## 5. 合并时实测到的文档滞后项（原 `AGENTS.md` §10 末句）

- 合并时实测到的文档滞后项（已按实测修正）：曾有文件写 Flyway「V1–V21」「V1–V43」，实际为 **V58**；
  曾有文件写移动端 bundle 预算 250 KiB，实际为 **entry 160 / lazy 180 KiB**。
