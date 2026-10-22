# 多 Agent 并行推进类拆分：可行性评估

日期：2026-10-21 ｜ 评估对象：按 `class-decomposition-execution-2026-10-20.md` 拆分 28 个超标类/文件，能否多 agent 并行
结论：**部分可行，但有硬上限 2 个并发，且不满足 §3 前置条件时不可行**

## 1. 结论

| 场景 | 结论 | 依据 |
| --- | --- | --- |
| 跨包独立拆分（`integration/wecom` ↔ `form/options` ↔ 前端页面） | ✅ **可行** | 生产代码零文件交集（实测），测试各自独立 |
| 同一变更面内的两个协调者（`FormDefinitionService` + `ProcessDefinitionService`） | ❌ **不可行** | 前者被 7 个包引用，`process` 包内即有 4 处；两者共享 schema 校验语义与测试夹具 |
| 引擎 `ProcessEngine` ↔ `WorkflowRuntimeV2` | ❌ **不可行** | 互相调用 + 共享 `t_task` 状态机；拆错无法靠单侧测试发现 |
| 改公开签名的任何重构 | ❌ **不可行** | 5 个类共 11 处手动 `new`，任一处签名变化即波及对方编译 |
| 门禁/基线维护 | ❌ **必须串行** | `baseline.json` 是单一写入点 |
| **并发上限** | **2 个 agent** | 空闲内存 8.4 GB（实测）；surefire 未设 `Xmx` |

**一句话**：能并行的不是"28 个类平分给 3 个 agent"，而是"包边界上互不相干的 2 条线"。

---

## 2. 判断依据（实测）

### 2.1 文件与模块交集

| 巨类 | 生产调用包 | 与谁天然不相干 |
| --- | --- | --- |
| `WecomService` | `integration/wecom`（仅包内） | 全部（除前端 `Wecom.tsx`） |
| `OptionSourceService` | `form/options`（仅包内） | 全部 |
| `FormDefinitionService` | `engine`、`form`、`form/options`、`form/runtime`、`mobile/workflow`、`process`、`task` | 前端、`integration/wecom` |
| `ProcessEngine` | `automation`、`engine/handler`、`mobile/workflow`、`task` | 前端、`integration/wecom`、`form/options` |
| `WorkflowRuntimeV2` | `engine`、`engine/handler`、`mobile/workflow`、`task` | 同上 |

**可并行的判据不是"类不同"，而是"包不相交"**。`WecomService` 与 `OptionSourceService` 的生产代码零交集；
而 `FormDefinitionService` 与 `ProcessEngine` 看似两个包，实际 `ProcessEngine` **直接持有**
`FormDefinitionService` 依赖（`engine` 包内唯一引用它的文件就是 `ProcessEngine.java`），
另在 `process`（4 处）、`task`（2 处）、`mobile/workflow`（4 处）、`form/runtime`（4 处）被引用 —— 属于同一变更面。

### 2.2 资源与隔离条件（本机实测）

| 维度 | 实测值 | 对并发的影响 |
| --- | --- | --- |
| CPU | 24 逻辑核 | 不是瓶颈 |
| 内存 | 总 31.8 GB，**空闲 8.4 GB** | **硬上限来源**：每个后端测试 JVM 1–2 GB + PG 容器 ~150 MB + 前端 build 1–2 GB |
| surefire | **未设 `Xmx` / `forkCount`** | 默认堆上限为物理内存 1/4（≈8 GB），两个 JVM 可能互相挤爆 → 必须显式钉堆 |
| 测试库 | 未设 `ANTFLOW_TEST_POSTGRES_URL` 时走 **Testcontainers**（每 JVM 独立容器） | ✅ 天然隔离；一旦设了外部库，两个 agent 共用同一库 → Flyway 并发迁移 + 测试数据互踩 |
| 构建目录 | `backend/target/`、`frontend/.umi/`、`dist` 均在工作区内 | ❌ 同目录并行编译**必炸**（产物互相覆盖） |
| 前端产物路径 | 支持 `ANTFLOW_OUTPUT_PATH` 覆盖 | ✅ 已有并行友好设计 |
| E2E 端口 | `E2E_PORT` 默认 **5174**（固定） | ❌ 两个 agent 同时跑 E2E 抢同一端口 |
| Docker | 运行中，已有 6 个容器 | 每多一个 Testcontainers PG，多一份内存与端口 |
| 磁盘 | 589 GB 可用 | 充足，worktree 不是问题 |

### 2.3 一个反直觉的有利结论：门禁不是冲突源

上一轮担心"每个 agent 拆完都要改 `baseline.json` → 必冲突"。实际相反：

> **拆分让 H7 数字下降，而 H7 只拦"变大"。所以 agent 完成拆分后，即使不改基线，门禁也是绿的。**

因此正确约定是：**拆分 PR 一律不碰 `baseline.json`**，由集成者在批次合并后统一 `--write` 收紧。
这样最大的共享写冲突点直接从流程里消失，而不是靠"小心合并"。

---

## 3. 前置条件（缺一不可）

| # | 条件 | 不满足的后果 |
| --- | --- | --- |
| P1 | **工作区干净**：当前有 **62 个未提交改动**（33 个 java），必须先提交或 stash | 并行会把这些改动搅在一起，冲突无法归因 |
| P2 | **每个 agent 独立 `git worktree`**，不在同一目录构建 | `target/`、`.umi/` 互相覆盖，表现为随机编译错误 |
| P3 | **不设 `ANTFLOW_TEST_POSTGRES_URL`**（走 Testcontainers），或给每个 agent 独立 database | 共享库并发 Flyway 迁移 + 数据互踩，测试随机红 |
| P4 | **显式限制堆**：`MAVEN_OPTS=-Xmx1g`、`-DargLine=-Xmx1g` | 2 个 JVM 各吃到 3–4 GB，8.4 GB 余量耗尽 |
| P5 | **端口错开**：`E2E_PORT=5175`（第二个 agent）；前端 `ANTFLOW_OUTPUT_PATH=dist-b` | E2E 抢 5174、产物目录互覆盖 |
| P6 | **契约冻结**：只搬内部实现，不改公开方法与构造器签名 | 对方编译失败；合并冲突从"文件级"升级为"签名级" |
| P7 | **依赖不交叉**：任务按包边界划分，且两任务之间无 Maven 模块依赖 | 见 §2.1 |

**首次并行还需**：每个新 worktree 各跑一次 `npm ci`（`node_modules` 不在版本库内），约 3–10 分钟；
后端 `~/.m2` 可共享（Maven 有 artifact 锁，偶发 "Could not acquire lock" 重试即可）。

---

## 4. 任务拆分方式：按包边界，不按文件数

### 4.1 推荐的两条并行线

| 线 | 范围 | 为什么可以并行 |
| --- | --- | --- |
| **A 线** | `integration/wecom/**`（`WecomService` → 配置/同步/映射/消息） | 生产调用方只在本包；测试仅 5 个文件，且不引用引擎 |
| **B 线** | `form/options/**`（`OptionSourceService` → CRUD/运行时/HTTP/守卫） | 生产调用方只在本包；无事务、无引擎状态 |

两条线的**新增文件、修改文件、测试文件**均不相交；唯一共用的是构建基础设施（由 P2–P5 隔离）。

### 4.2 必须串行的三条线

| 线 | 范围 | 串行原因 |
| --- | --- | --- |
| **C 线** | `form/**`（`FormDefinitionService` 校验/投影/规范化） | 扇出 7 包；一旦顺手改签名，B 线、D 线全部编译失败 |
| **D 线** | `engine/**`（`ProcessEngine`、`WorkflowRuntimeV2`） | 两者互相调用；共享 `t_task` 状态机与并行网关计数；改错只有全量集成测试能发现 |
| **E 线** | `process`、`task`、`mobile/workflow` | 都依赖 C/D 的公开契约 |

**串行不是保守，是依赖关系的直接结论**：C/D/E 共享"审批推进"这一份状态机语义，
任何一侧改动都会改变另一侧测试的预期。

### 4.3 前端可以并入 A/B 线

前端页面拆分与后端**零文件交集**，可挂在任一并行线上（但同一时刻只能有一个 agent 跑前端 build，
因为 `.umi` 与 `dist` 只能由 P5 隔离）。

---

## 5. 并发冲突与状态同步风险

| 风险 | 层级 | 能否隔离 | 表现 | 对策 |
| --- | --- | --- | --- | --- |
| 构建产物互相覆盖 | 文件 | ✅ worktree | 随机编译错误、测试报告错乱 | P2 |
| 测试库并发迁移 | 数据 | ✅ 独立库/容器 | Flyway 校验失败、随机数据断言失败 | P3 |
| 端口抢占 | 运行时 | ✅ 环境变量 | E2E 启动失败 | P5 |
| **签名变更波及对方** | 编译 | ❌ | 合并后编译失败，且**只有合并时才发现** | P6 + 合并前各跑一次全量编译 |
| **语义漂移** | 行为 | ❌ **无法隔离** | A 线测试绿、B 线测试绿，合并后集成测试红 | 红线"行为不变"；合并后必须跑全量 `mvn test` |
| 基线数字归属 | 度量 | ✅ 约定 | 无法判断谁真正拆了 | 拆分 PR 不改 baseline；集成点 `--write` 并核对下降量 |
| ADR 追加冲突 | 文档 | ✅ 约定 | `DECISIONS.md` 尾部冲突 | 各 agent 写 `docs/adr/<topic>.md`，或集成者统一写 |
| 内存争抢 | 资源 | ⚠️ 部分 | GC 抖动、测试超时（曾有 30s 阈值 flake 历史） | P4 + 并发上限 2 |

**最需要警惕的是"语义漂移"**：它同时躲过两边的测试，只在合并后才暴露。
这也解释了为什么"一次只搬一个方法簇、每簇独立 commit"必须坚持——它把语义漂移的排查范围限制在一个提交内。

---

## 6. 重点关注问题

1. **并发上限是内存决定的，不是 CPU**。24 核可以跑 6 个 agent，但 8.4 GB 空闲只够 2 个后端测试 JVM。
   若坚持 3 个，必须让第三个只做纯文档/前端，不同时跑后端测试。
2. **不要让两个 agent 都跑全量 `mvn test`**。全量约 700+ 用例、含真库容器，单个就要数分钟与 1–2 GB；
   并行时改为"各跑目标类测试 + 合并后串行跑一次全量"。
3. **契约冻结要写进任务书**，而不是靠 agent 自觉。具体措辞：禁止修改 public 方法签名、构造器参数顺序、
   注解、类名；新增协作者只能通过构造器注入并同步修改全部 `new` 点（若该点在测试里，属于同一 PR）。
4. **合并顺序有讲究**：先合零风险（包内拆分、文档），再合桌面端，最后合 C/D 线。
   顺序反了会让后合并者承担全部冲突。
5. **62 个未提交改动是当前最大障碍**（P1）。在它们落地之前开并行，等于在流沙上盖楼。
6. **`antflow-api.json` 应始终零 diff**。它若在并行期间变化，说明有人改了接口，此时应停止合并而不是接受新契约。

---

## 7. 验证方案：用一次双线试点决定是否推广

### 7.1 试点设计

选 **A 线（`WecomService`）+ B 线（`OptionSourceService`）**，两个 agent、各一个 worktree、同时开工。
这两条线是实测下唯一同时满足"零生产交集 + 低风险 + 有现成测试"的组合。

### 7.2 量化门槛（全部满足才算并行方案成立）

| # | 指标 | 门槛 |
| --- | --- | --- |
| 1 | 合并冲突文件数 | **0**（含 `baseline.json`；若为 1，说明约定被破坏） |
| 2 | 合并后 `cd backend && mvn -B test` | **全绿**（必须由集成者串行跑一次，不看 agent 自述） |
| 3 | `frontend/config/antflow-api.json` | **零 diff** |
| 4 | H7 下降量 | 等于两条线之和（用 `node scripts/health-metrics.mjs` 实测，不接受口头汇报） |
| 5 | 墙钟时间 | **< 串行预估的 70%**（否则并行不值得付 P1–P7 的基建成本） |
| 6 | 测试 flake 次数 | 0（若有超时，先排查是否为内存争抢） |

### 7.3 判定

- 6 项全过 → 可推广到"2 条并行线"的常态工作方式，C/D/E 仍串行；
- 第 5 项不过 → 退回串行，并行只用于"一个 agent 拆类、另一个只做文档/门禁"这类异构组合；
- 第 1/2 项不过 → 说明前置条件未真正落实，先修基建再谈并行。

### 7.4 每次合并前的固定检查（不可省）

```bash
# 集成者在一个干净 worktree 里执行，按顺序
node scripts/health-metrics.mjs                 # H7 必须下降，且不报变差
git diff --stat -- frontend/config/antflow-api.json   # 必须为空
cd backend && mvn -B test                       # 串行跑一次全量
```

**H7 有一个必须人工补的盲区**：它只比较"超标类数量与最大值"，不追踪"是哪几个类"。
若一次拆分把 1403 行降到 700 行、同时又新增一个 450 行的类，`backendOverLines` 仍是 16、
`maxClassLines` 反而下降 —— 数字变好，但债务只是转移了。

所以合并时必须同时看 `h7Offenders()` 打印的**文件清单**，确认：

1. 清单里没有本次新出现的文件名（新类应 ≤400 行）；
2. 清单里原有文件的排名在下降（而不是被新文件挤下去）。

这一条无法自动化（需要知道"哪些文件本轮该消失"），但只需 30 秒人工比对，漏掉的代价是整个门禁失去意义。

---

## 8. 最终结论

| 问题 | 回答 |
| --- | --- |
| 能否多 agent 并行？ | **部分可以**：仅限包边界互不相交的 2 条线 |
| 上限几个？ | **2 个**（内存 8.4 GB 决定），且不同时跑全量后端测试 |
| 哪些适合并行？ | `integration/wecom` ↔ `form/options` ↔ 前端页面（三者两两组合） |
| 哪些必须串行？ | `form`（`FormDefinitionService`）、`engine`、`process`/`task`/`mobile` —— 以及所有改签名的操作、基线维护、最终全量验证 |
| 现在能开始吗？ | **不能**：先清掉 62 个未提交改动（P1） |
| 怎么验证？ | §7 的双线试点 + 6 项量化门槛 |
| 主要风险？ | 语义漂移（无法隔离，只能靠"一簇一提交 + 合并后全量测试"兜住） |
