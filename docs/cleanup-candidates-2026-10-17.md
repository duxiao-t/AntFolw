# 仓库清理候选清单

> 扫描时间：2026-10-17 14:20–14:40（+08:00）
> 扫描范围：`E:/code/ant-flow` 全仓库（含各级子目录）
> 扫描方式：**只读**（`git status/log/ls-files/check-ignore`、`du`、`find`、引用检索）。**未删除、未移动任何内容**。
> 路径约定：以下路径均相对 `E:/code/ant-flow/`；"体积"为扫描时的实测值。

## 判定口径

每项按四个问题判定，并在"影响"列标明它影响的是**构建 / 依赖 / 版本控制 / 运行**中的哪一面：

1. 是否被 Git 跟踪（跟踪 = 删除会产生仓库变更，需团队决策）；
2. 是否被构建链引用（`Dockerfile.local`、`deploy.sh`、CI、package 脚本）；
3. 是否被运行链路引用（容器启动、后端启动参数、备份恢复脚本）；
4. 是否可再生产（重新构建 / 重新生成 / 重新下载能否得到同样内容）。

## 摘要

| 分组 | 项数 | 合计体积 | 处置性质 |
| --- | --- | --- | --- |
| A. 可直接清理 | 14 | 约 4–5 MB | 立即回收，零功能影响 |
| B. 建议保留待确认 | 7 | 约 1.12 GB | 需你确认是"归档"还是"删除"，其中 1.1 GB 是备份 |
| C. 需谨慎保留 | 8 类 | 约 0.34 GB + 依赖树 | 删除会破坏构建/运行/回滚能力 |

一句话结论：**真正的垃圾只有约 4–5 MB（A 组）；空间占用的大头（1.1 GB）是备份，属于"该归档不该删"；而 `node_modules` 与 `backend/target` 删了会让下一次部署必须先重新构建。**

---

## A. 可直接清理（14 项）

判断依据统一为：**未被 Git 跟踪（已在 `.gitignore` 中）+ 不被构建/运行/文档引用 + 内容可再生产或已过期**。

### A1–A3 根目录与后端的运行日志

| 路径 | 类型 | 体积 | 无关原因 | 建议处理 | 影响 |
| --- | --- | --- | --- | --- | --- |
| `frontend-run.err` | 文件 | 340 KB | 2026-08-22 手动启动前端时的 stderr 重定向（内容为旧构建告警），`.gitignore` 显式排除（`/frontend-run.err`） | 删除 | 无（构建、依赖、运行均不读取；版本控制已忽略） |
| `frontend-run.log` | 文件 | 649 B | 同上，stdout 重定向，忽略规则 `*-run.log` | 删除 | 无 |
| `backend-run.log` | 文件 | 11.8 KB | 2026-08-22 手动启动后端时的输出，忽略规则 `*-run.log` | 删除 | 无 |

### A4 后端根目录的 21 个运行日志

| 路径 | 类型 | 体积 | 无关原因 | 建议处理 | 影响 |
| --- | --- | --- | --- | --- | --- |
| `backend/audit-mvn-test.err.log`、`audit-mvn-test.out.log` | 文件 | 小 | 历史 `mvn test` 输出，忽略规则 `backend/audit-*.log` | 删除 | 无 |
| `backend/run-single.err.log`、`run-single.out.log` | 文件 | 小 | 单用例调试输出，忽略规则 `backend/run*.log` | 删除 | 无 |
| `backend/run18081.log`、`run18081.err.log`、`run18081-current.log`、`run18081-current.err.log` | 文件 | 小 | 18081 端口调试实例日志，忽略规则同上 | 删除 | 无 |
| `backend/run8081.log`、`run8081.err.log`、`run8081-current.log`、`run8081-current.err.log`、`run8081-live.log` | 文件 | 小 | 8081 端口调试实例日志，忽略规则同上 | 删除 | 无 |
| `backend/run2.err.log`、`run2.out.log` | 文件 | 小 | 二次运行输出 | 删除 | 无 |
| `backend/server-8080.err.log`、`server-8080.out.log`、`server-8081.err.log`、`server-8081.out.log`、`server-error.log`、`server.log` | 文件 | 小 | 手动起服务时的重定向，忽略规则 `backend/server*.log` | 删除 | 无 |

> 注：`backend/*.log` 全部命中 `.gitignore` 的 `backend/run*.log`、`backend/server*.log`、`backend/audit-*.log` 三条规则，因此不可能被误提交。

### A5–A6 前端与移动端的 dev server 日志

| 路径 | 类型 | 体积 | 无关原因 | 建议处理 | 影响 |
| --- | --- | --- | --- | --- | --- |
| `frontend/dev-8004.err.log`、`dev-8004.out.log`、`dev-8012.err.log`、`dev-8012.log`、`dev8010.err.log`、`dev8010.log`、`dev.err.log`、`dev.out.log`、`dev-server.log`、`dev-server-error.log` | 文件 | 小 | 本地 Umi dev server 重复启动留下的输出（同一天多个端口），忽略规则 `frontend/dev*.log` | 删除 | 无。**但如果此刻有 dev server 在跑，文件被进程占用，需先停进程** |
| `frontend/preview-8002.err`、`preview-8002.out`、`preview-8012.err.log`、`preview-8012.log`、`preview-server.log`、`preview-server.err.log`、`preview-server.out.log`、`preview-server-error.log`、`run-single.err.log`、`run-single.out.log` | 文件 | 小 | 本地预览服务器输出，忽略规则 `frontend/preview-*`、`frontend/run-single` 同类 | 删除 | 无（同上，占用需先停进程） |
| `mobile/dev-5173.log`、`dev-5173.err.log`、`dev-5173.out.log`、`dev-5174.log`、`dev-5174.err.log`、`dev5173.log`、`dev5173.err.log`、`dev.err.log`、`dev.out.log`、`vite-mobile.err.log`、`vite-mobile.out.log` | 文件 | 小 | 移动端 Vite dev server 多端口重复启动的输出，忽略规则 `mobile/dev*.log` | 删除 | 无（同上） |

### A7–A10 误建的目录与一次性会话残留

| 路径 | 类型 | 体积 | 无关原因 | 建议处理 | 影响 |
| --- | --- | --- | --- | --- | --- |
| `frontend/frontend/` | 文件夹 | 364 KB（3 个 PNG） | 在 `frontend/` 目录内又误执行了一次 npm/预览命令，生成的**嵌套同名目录**；`.gitignore` 里专门写了 `frontend/frontend/  # Frontend local preview artifacts` 说明团队已知此现象 | 删除 | 无（内含 `mobile-publish-preview*.png`、`section-designer.png` 三张调试截图，均非产品资产） |
| `frontend/.tmp/` | 文件夹 | 小（4 个日志） | 本地预览/后端联调时的临时输出目录 | 删除 | 无 |
| `.superpowers/brainstorm/` | 文件夹 | 61 KB | 2026-07-29 一次性 brainstorming 会话产物：页面草稿 HTML、`state/server.pid`、`state/server-stopped`、`.last-port`、**`.last-token`**；服务早已停止 | 删除；若担心令牌仍有效，先确认其失效（该文件是本地一次性服务令牌） | 无。注意：入库的设计文档在 `docs/superpowers/plans/`（**不要动**），与本目录无关 |
| `.worktrees/` | 文件夹 | 0（空目录） | 被忽略且为空；Git 不记录空目录，里面没有任何内容可丢 | 删除 | 无 |
| `node_modules/`（**根目录**那个） | 文件夹 | 4 KB | 根目录**没有** `package.json`，这是误在仓库根执行 vitest 留下的缓存（仅含 `.vite/vitest`） | 删除 | 无（根目录不存在 Node 工程） |
| `backend/src/test/java/com/antflow/task/.TaskOperationServiceTest.java.28304.1cb1a2e5-39dd-4fc1-b0fe-4df57e6c5b80.tmpdir/` | 文件夹 | 小 | 单元测试被中断后残留的临时目录，**直接躺在源码树里**（忽略规则 `*.tmpdir/`）；正常运行不会产生常驻内容 | 删除 | 无（测试运行时自建自清，不入库） |

### A11–A14 与脚手架/设计探索相关的图片

| 路径 | 类型 | 体积 | 无关原因 | 建议处理 | 影响 |
| --- | --- | --- | --- | --- | --- |
| `_preview/form-new.png` | 文件 | 178 KB | **已开图核对**：内容是 Ant Design Pro 脚手架的默认登录页（示例文案"Ant Design 是西湖区最具影响力的 Web 设计规范"、账号 `admin or user`），与 AntFlow 产品界面无关 | 删除或移入归档 | 无。已确认 `_preview/*.html`、`preview.js/css` 都不引用任何 `.png` |
| `_preview/form-new-auth.png` | 文件 | 149 KB | 同上系列（脚手架页面），未被任何文档引用 | 删除或移入归档 | 无 |
| `_preview/select-editor-empty.png`、`select-editor-empty2.png` | 文件 | 约 20 KB | 早期"选择器空态"设计探索的成对留存（`empty2` 表明反复试验），已被现有 UI 取代，未被引用 | 删除或移入归档 | 无 |
| `backend/target/surefire-reports/*.dumpstream`（381 个） | 文件 | 含在 5.5 MB 的 `surefire-reports/` 内 | 测试进程崩溃时的 dump 流，属构建目录内的中间产物 | 随 `backend/target/` 一起处理（见 C2） | 无 |

> 关于 `README.md`：文档里引用的截图路径是 `docs/images/process-designer.png` 等**未来路径**，与 `_preview/*.png` 无关；A11–A13 三组删除后 README 不会出现死链。

---

## B. 建议保留待确认（7 项）

判断依据：**不是垃圾，但存在"占空间大 / 可能仍有价值 / 用途待核实"三种情况之一，处置需要你的判断。**

| # | 路径 | 类型 | 体积 | 判断依据（为什么不能直接删） | 建议处理 | 影响 |
| --- | --- | --- | --- | --- | --- | --- |
| B1 | `backups/` | 文件夹 | **1.1 GB** | 内含 3 份加密备份 `antflow-20260915-183031.afbackup`、`antflow-20260916-183036.afbackup`、`antflow-20260918-081429.afbackup`（各 27 MB）与 3 个 `predeploy-20260918-*` 目录（305/324/358 MB）。它们是 `backend/backup/restore-backup.sh` 的**唯一输入**，且**不可再生产** | 归档到仓外/冷存储；或只保留最近一份（2026-09-18）再归档其余两份 | 删除后失去 9 月中旬的回滚点（今天部署的是 master 最新代码，且本次无数据库迁移，短期不依赖它）；对构建、依赖无影响；已在 `.gitignore` 中，不影响版本控制 |
| B2 | `_perf/`（其中 `_perf/write-load/` 9.5 MB） | 文件夹 | 9.6 MB | 目录内混有两类东西：可复用压测脚本（`load-test.mjs`、`media-load.mjs`、`prod-server.mjs`）与一次性历史结果（`write-load/`、`*.jsonl`、`.gz` 日志）。README 写明压测结果写入 `_perf/write-load/` | **脚本保留**；历史结果归档 | 丢弃结果会失去性能基线的一手证据（正式报告在 `docs/performance-load-test-2026-08-24.md`，不依赖本目录）；对构建、依赖、运行无影响 |
| B3 | `ant-design-pro-master/` | 文件夹 | 3.8 MB | 脚手架原始副本，**未入库**。依据 `docs/superpowers/plans/2026-07-15-antflow-fusion-impl.md:32/1092/1099`：`frontend/` 是由它 `cp -r` 得到的，收割已完成 | 确认无人再对照后归档或删除 | 不影响构建（`frontend/` 已独立）；不影响版本控制（从未入库）；删除后如需再查脚手架需重新下载 |
| B4 | `wflow-master/` | 文件夹 | 2.3 MB | **被 `CLAUDE.md:49` 明确引用**："流程定义…是单棵递归树，参考实现见仓库内 `wflow-master/`"。删除会让该文档引用悬空 | **保留**；若确实要清，先更新 `CLAUDE.md` 的引用 | 不影响构建与运行；影响的是协作文档的可追溯性 |
| B5 | `backend/archunit_store/` | 文件夹 | 8 KB（4 个文件，**已入库**） | ArchUnit 架构规则的缓存（`stored.rules` + 3 个 UUID 文件）。可再生产，但**已被跟踪**，删除属"改版本控制"的动作 | 保留（或团队确认后从仓库移除并加入 `.gitignore`） | 删除不影响构建与运行；会让首次 ArchUnit 检查变慢；会产生一次仓库变更 |
| B6 | `_preview/process-designer-1024x768.png`、`-1440x900.png`、`-1920x1080.png`、`-branch-drawer.png`、`-branch-condition-drawer.png`、`-trigger-drawer.png` | 文件 | 约 630 KB | 真实流程设计器截图（三种分辨率 + 分支条件/触发节点抽屉），**正是 README"待补充截图"需要的素材**。未被引用是因为 README 期望它们位于 `docs/images/` | 保留待用（若采用，复制到 `docs/images/` 后即可在 README 生效） | 无（PNG 未入库，不影响版本控制） |
| B7 | `backend/data/mobile-files/` | 文件夹 | 1 MB（6 个文件） | 本地文件存储落地目录（`MOBILE_FILE_STORAGE=local` 时的上传物）。可能是早期联调的测试附件，**也可能含真实数据** | 先查看内容再决定；若有业务数据 → 移入归档 | 仅本地存储模式使用；Docker 栈用 MinIO，不读这里 |

---

## C. 需谨慎保留（8 类）

判断依据：**被构建链或运行链直接引用，删除会导致"必须先重建/重配"才能恢复**。它们不是垃圾，清单列出是为了让你知道"动了会付出什么代价"。

| # | 路径 | 类型 | 体积 | 引用关系（删除会付出什么） | 建议 |
| --- | --- | --- | --- | --- | --- |
| C1 | `frontend/node_modules/` | 文件夹 | 顶层 1236 个包（子树数万文件） | 构建链：`npm run build`、CI `npm ci` 的依赖源；`deploy.sh` 在 `package-lock.json` 未变时**故意复用**它们以省时间 | 保留。删除后必须 `npm ci` 才能构建或出镜像（影响构建、依赖） |
| C2 | `mobile/node_modules/` | 文件夹 | 顶层 322 个包 | 同上（移动端） | 保留（同上） |
| C3 | `backend/target/` | 文件夹 | **328 MB**（含 101 MB 可执行 JAR、1.2 MB `.jar.original`、5.5 MB surefire-reports、3.9 MB classes） | **`Dockerfile.local` 第 18 行直接 `COPY backend/target/antflow-backend-0.1.0-SNAPSHOT.jar`** | 保留。若清理，清理后**必须先** `mvn -B -f backend/pom.xml -Dmaven.test.skip=true package` 再执行 `docker compose build`（影响构建、镜像、运行） |
| C4 | `frontend/dist/` | 文件夹 | 7.4 MB | **`Dockerfile.local` 的 web 阶段直接 COPY**；且当前这份是今天 14:0x 部署时构建、与运行中容器内容**逐字一致**的版本（`umi.7eb01edc.js`） | 保留。删除会使 `docker compose build web` 失败，除非先重新 `npm run build`（影响构建、镜像、运行） |
| C5 | `mobile/dist/` | 文件夹 | 1.6 MB | 同上；当前与容器内 `/usr/share/nginx/html/mobile/` 一致（`assets/index-B8baw_SB.js`） | 保留（同上） |
| C6 | `.env.docker.local` | 文件 | 802 B | **运行必需**：8 个密钥/口令（数据库、MinIO、JWT、审计归档、集成、备份、初始管理员）。缺失则 Compose 直接报错退出 | **绝不删除、绝不入库**（已忽略）。如需清理请另行做密钥轮换，而非删文件 |
| C7 | `.workbuddy/` | 文件夹 | 小 | 工作区记忆与项目数据（本会话写入的 memory 日志），按约定不作为缓存清理 | 保留；**但它目前未被 `.gitignore` 覆盖**，存在被 `git add .` 误提交的风险 → 建议补一条忽略规则（配置变更，非删除） |
| C8 | 入库的工程与文档资产 | 文件/文件夹 | `docs/` 988 KB（32 个入库文件，含 `docs/superpowers/plans/` 11 份计划 488 KB）、`_preview/` 下 23 个入库文件、`backend/smoke-backend.bat`、`smoke-idem-only.py`、`smoke-live.py` 等 | 全部被 Git 跟踪，且被 `README.md`、`AGENTS.md`、`CLAUDE.md` 或 CI 引用 | 保留 |

> 另外保留：`.github/`（CI）、`.gitattributes`、`.gitignore`、`.dockerignore`、`Dockerfile.local`、`Dockerfile.test`、`Dockerfile.test.dockerignore`、`compose.yaml`、`compose.test.yaml`、`compose.write-load.yaml`、`deploy.sh`、`scripts/`、`infra/`、`fixtures/`、`shared/`、`LICENSE`、`README.md`、`AGENTS.md`、`CLAUDE.md`、`codex.md`、`agent.md`、`Engineering discipline.md` —— 均为入库且在用的工程文件。

---

## 附录一：本次**未**发现的问题

| 类别 | 结论 |
| --- | --- |
| 编辑器/系统垃圾 | 未发现 `.idea/`、`.vscode/`、`*.iml`、`.settings/`、`.DS_Store`、`Thumbs.db`、`*.swp`（`.gitignore` 已提前覆盖，实际也不存在） |
| 重复依赖锁文件 | 未发现多份 `package-lock.json` 冲突或 `yarn.lock`/`pnpm-lock.yaml` 混用 |
| 构建缓存 | `_preview`、`_perf`、`node_modules` 之外没有额外的缓存目录；Docker 构建缓存由 Docker 自身管理（不在仓库内） |
| 废弃的编排文件 | `compose.test.yaml`（隔离测试栈）与 `compose.write-load.yaml`（压测栈）均在用，非废弃 |

## 附录二：仓库**之外**、由本次会话产生的待清理项

| 路径 | 类型 | 体积 | 说明 | 建议 |
| --- | --- | --- | --- | --- |
| `E:/code/_build-junk-20261017/` | 文件夹 | 9.8 MB | 今天为绕过环境删除保护而**移出**的旧产物：`frontend_dist`、`mobile_dist`、`frontend_src_.umi*`（旧构建缓存）、`README.zh-CN.md.superseded`（被替换的 README 变体） | 可整体删除（其中的 README 变体内容已与当前 `README.md` 逐字一致） |
| `docs/deploy-master-runbook-2026-10-17.md` | 文件 | 小 | 今天的部署 runbook，**未跟踪** | 由你决定提交入库或删除 |

## 附录三：动手前的三条提醒

1. **A 组可以一次清完**，但 `frontend/`、`mobile/` 的 dev 日志若被运行中的 dev server 占用，删除会失败——先确认没有本地 dev server 在跑（今天部署时我已确认宿主机无 antflow 后端进程；前端 dev server 未检查）。
2. **C 组不要为了"看起来干净"而清**：`target/` 与两个 `dist/` 是 `Dockerfile.local` 的输入，删了就要重建，否则下一次 `docker compose build` 会直接失败。
3. **本清单未执行任何动作**。你确认后我再按"分组 + 项号"执行；执行时我会逐组小批量操作、并在每组后核对 `git status` 与容器状态。

---

## 执行记录（2026-10-17 14:45–14:47）

**已执行的授权范围**：A 组全部 14 项 + 仓库外旧暂存目录 `E:/code/_build-junk-20261017`。

**处理方式**：**移入回收目录 `E:/code/_antflow-trash-20261017/`，未做硬删除**。
目录内保留了原路径层级，并生成 `RESTORE.md`（恢复与永久删除说明）与 `MANIFEST.txt`（347 个文件含字节数的完整清单）。

**实测回收体积：约 3.2 GB**（原预估"4–5 MB"，严重低估）。原因：`backend/` 根目录的"运行日志"里有 6 个**失控膨胀的巨型日志**——
`run18081-current.log` 1.1 GB、`run8081-current.log` 692 MB、`server.log` 574 MB、`server-8080.out.log` 418 MB、
`server-8081.out.log` 415 MB、`run-single.out.log` 117 MB（均为 2026-08-16 ~ 08-24 的 dev 实例输出）。

**执行后校验**：

| 校验项 | 结果 |
| --- | --- |
| `git status --short` | 仅剩 3 个未跟踪文件：`.workbuddy/`、`docs/cleanup-candidates-2026-10-17.md`、`docs/deploy-master-runbook-2026-10-17.md`；**无被跟踪文件被改动** |
| 忽略项数量变化 | `frontend/` 26 → 4、`backend/` 24 → 2、`mobile/` 14 → 3；根级 `frontend-run.*`、`backend-run.log`、`node_modules/`、`.superpowers/`、`.worktrees/` 全部消失 |
| C 组产物完好 | `frontend/dist`、`mobile/dist`、`backend/target/*.jar`、`.env.docker.local` 均 OK，未触碰 |
| B 组未动 | `_preview/process-designer-*.png` 6 张、`backups/`、`_perf/`、`ant-design-pro-master/`、`wflow-master/` 原样保留 |
| 服务未受影响 | 4 个容器仍 Up（postgres/minio healthy），`/actuator/health` = UP，桌面端与移动端入口均 200 |
| 磁盘空间 | **同卷移动，暂未释放**；需永久删除回收目录后才真正回收约 3.2 GB |

**仍待确认（未执行）**：

- 回收目录 `E:/code/_antflow-trash-20261017/` 的永久删除（不可逆，见其 `RESTORE.md`）。
- 本次扫描中**新发现**的仓库外项目（不在原 A/B/C 清单内，均未触碰）：
  `E:/code/ant-flow.rar`（474 MB，2026-07-16 的项目快照）、`E:/code/AntFlow-activiti-master/` 与 `AntFlow-activiti-master.zip`（28 MB，早期上游参考项目）、
  `E:/code/RuoYi-springboot2/` 与 `RuoYi-springboot2.zip`（4.7 MB，若依框架，与 AntFlow 无直接关系）。

**补充（同日 15:00）**：`AGENTS.md`、`agent.md`、`codex.md` 与 `ant-design-pro-master/CLAUDE.md` 已按需求整合进新的根目录
`AGENT.md`（内容全部并入，冲突处理见其第 14 节），并从仓库移除。因此本清单 C8 行与"另外保留"段落中把这些文件描述为
"在用工程文件"的表述随之失效；原文可由 `git show HEAD:<文件名>` 取回。

**补充（同日，文档迁移时）**：根目录 `AGENT.md` 已按约定改名为 `AGENTS.md`，内容未变（整合后进一步收敛为 12 节，见
`docs/doc-split-plan-2026-10-17.md`）。本清单正文中出现的 `AGENT.md` 均指改名前的文件，属当时状态记录，不回改。
