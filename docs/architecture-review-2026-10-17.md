# AntFlow 架构评审（2026-10-17）

> 评审视角：资深架构师，只读通读 + 代码取证。
> 前置阅读：`README.md`、`docs/PROJECT.md`、`docs/HANDOFF.md`、`docs/architecture-review-2026-10-09.md`、`docs/DECISIONS.md`（索引）。
> 本评审**不复述**已立项的 P0–P5 路线，只标注"已知/已立项"与"新增发现"，并对每项给出影响与可落地方向。
> 结论先行：**架构方向是对的，不需要重构；问题集中在"历史包袱未清、双实现未收敛、数据库约束与治理滞后"三块。**

---

## 0. 评审范围与实测规模

| 区域 | 实测数据 |
| --- | --- |
| 后端主代码 | 30,285 行 / 20 个域包 / 33 个 Controller / 34 个 `@RestController` / 39 个 `@Service` |
| 后端测试 | 19,742 行 / 87 个测试类（含真库集成、ArchUnit、契约快照） |
| 桌面端 `frontend/src`（已跟踪） | 100,183 行 / 449 文件，**其中 55,221 行是未注册路由的 Ant Design Pro 演示页** |
| 移动端 `mobile/src` | 25,162 行 / 164 文件 |
| 共享契约 | `shared/api.generated.d.ts` 2,099 行（生成物）+ `shared/form-validation.ts` 96 行 |
| 数据库 | 56 个迁移文件（V1–V58，只前滚）/ 55 张表 / 5 个 BI 只读视图 / 3 个触发器函数 |
| 部署单元 | 4 个常驻容器（postgres / minio / backend / web）+ backup-init；后端 `-Xmx768m`，Hikari 10，调度池 4 |
| 定时任务 | 14 个 `@Scheduled`（outbox 1s 轮询、企微消息 1s、媒体 reaper 60s、备份 60s、企微同步/续租 30s…） |

**规模与场景匹配度结论**：面向中小团队到中大型企业的**单租户私有化内部审批**，桌面管理 + 移动审批 + 单实例一体化 Docker 部署。这个体量（后端 3 万行、55 张表、单实例）**模块化单体是正确选择**，文档中"不拆微服务、不做 BPMN 全量、不引事件溯源"的取舍也是对的。当前主要矛盾不是"服务拆得不够细"，而是**同一件事有三份实现、以及数据库约束落后于应用逻辑**。

---

## 1. 整体架构设计与技术选型概述

### 1.1 形态

- **模块化单体**，按**功能包**（package-by-feature）划分：`engine`（31 文件）、`org`、`attachment`、`form`、`authz`、`auth`、`mobile`、`task`、`common`、`integration`、`automation`、`audit`、`process`、`notify`、`backup`、`report`、`navigation`、`workplace`、`dev`。
- **两端前端 + 共享契约**：Umi Max/AntD Pro 桌面端、Vite/antd-mobile 移动端（部署基路径 `/mobile/`），两端类型同源于后端 OpenAPI 构建期快照（`frontend/config/antflow-api.json`，163 条 `/api/` 路径，逐字校验）。
- **数据边界**：单租户；公司/部门/角色 + 数据范围做内部分权；附件与审计归档落 MinIO；BI 走只读视图直连库（有意绕过应用鉴权）。

### 1.2 技术选型评价

| 选型 | 评价 | 说明 |
| --- | --- | --- |
| Spring Boot 3.3.4 / Java 17 | **合适** | 单体现在仍是主流最优解；17 是长期支持版本 |
| MyBatis-Plus + 少量 `JdbcTemplate` 手写 SQL | **合适但需纪律** | 两者混用本身没问题（复杂读写用 JDBC 更可控），但已有 21 个 Mapper 接口 + 28 处 `JdbcTemplate`，**行级权限拦截器只认 MyBatis 语句 id**，手写 SQL 天然绕过（见 A3） |
| PostgreSQL 17 + JSONB + ltree | **合适** | 表单 schema/值用 JSONB 是这类产品的正确解；ltree 表达部门树恰当 |
| Flyway 只前滚（V1–V58） | **正确** | 迁移即事实，历史冻结是纪律的体现 |
| MinIO | **合适** | 私有化场景优于云对象存储 |
| JWT + 数据库会话（30 天）+ OIDC + 企微免登 | **合适** | "外部身份只绑定已有本地用户"是克制的正确取舍 |
| Bucket4j 登录限流 | **合适** | 单实例内存限流足够；多实例时需换存储后端 |
| springdoc + 构建期契约快照 + 生成客户端 | **优秀** | 同规模项目里少见的自动化程度 |
| Umi Max / Vite 双构建 | **可接受** | 异构构建的成本换来了移动端体积可控（160/180 KiB 门禁）；不必强求统一 |
| Webhook 出站统一策略（白名单 + HTTPS + DNS 后地址校验） | **优秀** | 已收敛到共用策略 |

### 1.3 应当保留的正确决策（不要动）

1. **定义版本快照 + 实例冻结快照**（V5/V31）：在途实例不受新版本影响，历史可解释，是审批系统的命门。
2. **三层鉴权**（端点能力 → 资源授权 → 数据范围），并明确"前端与菜单不是安全边界"。
3. **表单"使用授权"与"模板维护"分离**（`t_form_resource_grant` / `t_form_maintainer`），最后一名的双重保护（服务层 + DB 触发器）。
4. **公司归属在创建时快照**（V54 三态：已确认 / 历史推断 / 待确认），并明确"NULL 的 false 不等于已确认"。
5. **outbox + 租约 + 幂等键 + 稳定事件键**：失败重放有权限、有审计。
6. **BI 台账按记录自己的版本解释**（`FormValueDisplay` + 视图回退），避免"字段改名后历史报表串味"。
7. **防漂移机制**：共享 fixture 四渠道对拍（H2 4/4）、架构边界门禁（ArchUnit 冻结 + TS 导入边界）、六健康指标"只禁变差"、契约生成物门禁。**这套机制比大多数同规模项目强得多，是本项目最大的工程资产。**
8. **守护用例文化**："去掉修复即失败"的反向验证，写进了交接文档。

---

## 2. 架构层面的问题

> 记号：**[已知]** = 已在 `docs/HANDOFF.md` / `architecture-review-2026-10-09.md` 立项；**[新增]** = 本轮新发现或未立项。
> 优先级：P0 = 低风险高收益、建议近期做；P1 = 结构改善、随迭代做；P2 = 触发条件驱动。

### A1 双运行时（V1/V2）在同一循环里交织 [已知，P1]

**现象**：`ProcessEngine` 的落地循环里 `runtimeV2 != null ? ... : ...` 分支出现 **25 处**（全文引用 `runtimeV2` 达 50 处），`enterNode` 返回 0 即回落 legacy；新实例固定写 `engineVersion = 2`（`ProcessEngine.java:140`），但仍要兼容 `engineVersion = 1` 的存量实例（投票、驳回仲裁、重提各有一套 `settleLegacy*` 实现）。`WorkflowRuntimeV2` 1148 行 + `ProcessEngine` 1258 行，两套语义并行存在。

**影响**：
- V1 无法物理删除——**它不是模块，是散落在主流程里的条件分支**，退役成本随每次改动上升；
- 任何流程语义改动都要在"新旧两套"上分别验证，缺陷面翻倍；
- 阅读者无法从代码看出"当前生效的是哪一套"，只能靠实例字段判断。

**优化方向**（沿用文档 P3 的思路，但补一个具体做法）：
1. 先让 `runtimeV2` 成为**必需依赖**（`@Autowired(required=false)` 去掉，缺失即启动失败），消除"回退到 legacy"的隐式路径；
2. 抽 `RuntimePort` 接口（`enterNode/completeNode/approve/rejectVote/outbox/beginRound/...`），V2 做唯一实现，V1 做**带 `@Deprecated` 且加日志计数**的实现；
3. 用"存量 V1 实例数 + 关联待办 + 活跃 job"三个查询作为**退役门禁**（文档已列 `SELECT engine_version,status,count(*)`），归零后删除 V1 实现与全部分支；
4. 退役前**冻结**：不再新增 V1 分支，新语义只进 V2（现状已经是这样，保持）。

### A2 待办/列表读模型双实现，且抄送是两套模型 [部分已知，P1]

**现象**：
- 桌面待办走 `TaskMapper` + `TaskController`；移动待办走 `MobileWorkflowMapper.selectTaskPage`（大段手写 SQL，含 6 列 `ILIKE` 关键字、`UNION ALL`、`LIMIT/OFFSET`）——**同一业务读模型两套实现**，语义靠人工对齐（HANDOFF 明确写了"桌面端与移动端同语义"需要人工保证）。
- 抄送有两个模型：`t_task.status = 'CC'`（V1，`CcHandler` 写入）与 `t_cc_record`（V2，`WorkflowRuntimeV2.recordCc`）。移动端用 `UNION ALL` 把两者拼成一张表，并把 `8000000000000000 + cc.id` 作为**任务 id** 返回给客户端（`MobileWorkflowMapper.java:90/319/378`），服务端再用 `id >= CC_TASK_ID_BASE` 反解（`MobileWorkflowService.java:55/317`）。

**影响**：
- 两端列表口径漂移（筛选、排序、状态桶、可见性）只能靠人肉保证；
- **合成 id 是隐式协议**：它出现在 API 契约里，客户端、服务端、测试三处都依赖这个 magic number，任何一侧改动静默破坏功能；
- CC 混在 `t_task` 里，使"任务"与"抄送"两个语义共享 `status`/`read_at`/`assignee_id` 列，状态机与统计口径都被污染。

**优化方向**：
1. 把"待办/已办/我发起"抽成**一份查询服务**（可以保留两套 SQL，但收敛到同一 service + 同一 DTO 投影），端点只做适配；
2. 抄送统一到 `t_cc_record`（V2 已是事实上的目标模型），V1 退役时删除 `t_task` 的 CC 行；
3. 抄送作为**独立资源**暴露（`/api/cc/**` 或在 DTO 里加 `kind: 'TASK'|'CC'`），**把 `8000000000000000` 从契约里删掉**；
4. 加一条跨端一致性用例（同一账号、同一数据集，桌面与移动返回的 id 集合与顺序一致）作为防漂移门禁。

### A3 行级数据权限是"白名单式、默认放行" [新增，P0]

**现象**：`DataPermissionPolicyHandler.java:31-34`——`DataPermissionRules.match(...) == null` 时**直接返回 null（不注入任何条件）**。而 `DataPermissionRules.RULES` 只登记了 **2 条**规则（`FORM_DATA`、`FORM_DEFINITION`）。同时第 36 行：`scope.isEmpty()`（无请求主体，即系统上下文）时也**不注入**。

**影响**：
- 新增任何受控查询，**忘记登记即静默全量放行**——"默认拒绝"只在端点能力层成立，行级层实际是"默认允许"；
- 请求主体丢失（异步线程、内部调用、线程池复用失误）会**直接退化为全量可见**，而这类 bug 不会报错、只会"多看到一些数据"；
- 手写 SQL（`JdbcTemplate`）天然不在拦截器覆盖范围内，只能靠人记得手动拼 `InstanceScopeSql`。

**优化方向**（成本很低，收益明确）：
1. 加一个**启动/CI 检查**：把所有 Mapper 语句 id 与 `RULES` 对账，凡出现 `t_form_data` / `t_process_instance` 等受控表的语句但未登记 → 启动失败或 CI 红（与现有 `PermissionCatalog` 启动校验同一思路，项目已有这个模式）；
2. 无请求主体时，**对管理端读接口 fail-closed**（`InstanceScopeSql` 已经这么做，把同一原则搬到拦截器：区分"系统任务"与"请求线程"两种上下文，而不是统一放行）；
3. 在 `DataPermissionRules` 补一条注释级约定：新增受控表读路径必须**同时**加规则 + 加负向用例。

### A4 调度线程池与外部投递/重活共用 [已知并已缓解，P1]

**现象**：14 个 `@Scheduled` 共用 `spring.task.scheduling.pool.size`（默认 4，`application.yml:32-39` 有明确注释），outbox 1s 轮询、单次最多同步投递 50 条（webhook 超时 5s）**就在调度线程上执行**；企微消息 1s 轮询、同步/续租 30s、媒体 reaper 60s 同池。文件处理有独立 `ThreadPoolTaskExecutor`（`FileProcessingConfig`，这点是对的）。

**影响**：外部依赖变慢时（企微、webhook、MinIO）调度池被占满 → 媒体 reaper / 备份 / 同步全部延迟；这是"单实例 + 单进程"架构下最容易在生产暴露的稳定性问题。文档已把租约续期改为不依赖调度线程（正确），但**投递本身仍在调度线程**、且池大小是"缓解"不是"根治"。

**优化方向**：
1. 投递搬出调度线程：`poll()` 只做"领取 + 投递到专用 executor"，或至少给 outbox 一个**独立线程池/队列**（与 reaper/同步物理隔离）；
2. outbox 领取改 `FOR UPDATE SKIP LOCKED` 批量领取 + 有界并发（现在是 `locked_by` 租约，已是正确方向，配合批量可提升吞吐）；
3. 给调度池加"任务耗时/排队"监控（actuator 指标），把"哪个任务饿死了别人"变成可观测事实。

### A5 可选依赖造成静默降级 [已知，P1]

**现象**：`@Autowired(required = false)` **15 处**（`FormDefinitionService` 3、`FormDataService` 2、`ProcessEngine`、`ProcessDefinitionService`、`MobileWorkflowService`、三个 NodeHandler 等）。其中 `ProcessEngine` 的 `runtimeV2` 缺失时，新实例仍会写入 `engineVersion = 2`（`:140`），但 V2 状态（节点实例、参与者、修订）不会落库——产生**"声明为 V2、结构为 V1"的混合实例**。文档已列此项（H5：runtimeV2 静默回退入口 1）。

**影响**：生产环境一旦漏注入（配置、profile、构造器改动），系统**可以正常启动、正常审批、但语义悄悄降级**，且这类数据事后无法自动修复。

**优化方向**：区分三类依赖并分别定规——①**产品必需**：构造器注入，缺失即启动失败（`runtimeV2`、`businessNumbers` 属此类）；②**产品可选功能**：保留 `required=false` 但用 `@ConditionalOnProperty` 显式表达，并在启动日志打印"已禁用 X"；③**仅供单测**：改为测试用构造器/工厂，不要留在生产类里。

### A6 媒体处理与业务进程同生共死 [部分已知，P2]

**现象**：ffmpeg 水印在**应用进程内**执行（图片同步、视频异步 + 并发闸门），后端镜像因 apt 装 ffmpeg 涨到 1.57GB，`-Xmx768m` 单实例；文档已记录磁盘成为新约束。

**影响**：单机 CPU/内存/镜像体积成为部署约束；水平扩展时每实例都要重复 ffmpeg + 字体，且转码无法独立扩缩。

**优化方向**：**现在不要拆**（规模未到）。设定触发条件：并发视频数持续 > 闸门值、或容器内存成为投诉项、或需要多实例部署时，把水印/时长探测抽为独立服务（HTTP + 队列），应用侧只保留"上传 → 登记 → 回调发布"。当前可先做的低成本项：换静态 ffmpeg 二进制（文档已列为 P4.4，退出条件明确）。

### A7 SSE 事件是进程内状态 [新增，P2]

**现象**：`MobileEventController.java:36` 用 `ConcurrentHashMap<Long, CopyOnWriteArraySet<SseEmitter>>` 保存连接，25s 心跳。

**影响**：**这是单实例假设**。一旦部署两个 backend 副本，A 副本产生的通知不会推给连在 B 副本上的用户——表现为"偶发收不到实时刷新"，且很难排查。

**优化方向**：现在保持单实例即可（`compose.yaml` 也是单副本）。把"SSE 需要粘性会话或广播通道（Redis pub/sub / PG LISTEN）"写进**水平扩展的前置清单**，与调度任务 leader 选举、内存限流一并处理。

### A8 实体、传输对象、展示字段未分离 [新增，P1]

**现象**：
- Controller 直接返回实体：`FormDefinitionController` 返回 `FormDefinition`（含内部字段），`TaskController.myInbox` 返回 `WorkflowPage<TaskEntity>`；
- 展示字段写进实体：`TaskController.fillNodeNames()` 给 `TaskEntity` 塞 `nodeName`（该字段只对前端有意义）；
- 请求体大量使用 `Map<String, Object>` + 手工 `asString(...)` 解析（`TaskController.approve/reject` 等），`spring-boot-starter-validation` 已在依赖里却没用在请求边界。

**影响**：API 契约随实体内部结构漂移（生成契约里"属性全可选"正是零注解的后果）；实体被塞展示字段后无法安全序列化；手工解析的请求体缺校验、缺类型、错在运行时才暴露。

**优化方向**：**不做全面 DTO 化**（成本高），只做三件事：①受控实体的敏感/内部字段加 `@JsonIgnore`；②新增端点用 `record XxxCommand(...)` + `@Valid`；③展示字段改由 `TaskRow` 这类投影承载，别再改实体。

### A9 前端巨型组件与请求层分散 [新增，P1]

**现象**：`FormManagementWizard.tsx` 1250 行、`proc/Detail.tsx` 965 行、`org/Contacts.tsx` 750 行、`settings/Wecom.tsx` 629 行；42 个页面文件直接调 `request()`（76 处）。移动端已是"每个 feature 一个 `*.api.ts`"的规范结构，两端不一致。

**影响**：巨型组件内联了表单、校验、权限、路由、请求，改动面大、回归成本高；请求分散使错误处理口径（401/403/422）难以统一。

**优化方向**：以移动端 `features/*/*.api.ts` 为模板，在桌面端按页面域抽 `services/<domain>.ts`；巨型页按"容器 / 表单 / 步骤 / 校验"拆，**不追求一次性拆完**——每次因需求触碰该文件时顺带拆一层即可（这类文件的拆解只有配了回归用例才安全，项目测试足够，做得到）。

---

## 3. 数据库设计问题

### B1 双份数据与冗余列 [部分已知，P0/P1]

| 现象 | 影响 | 优化方向 |
| --- | --- | --- |
| `t_form_data.data` 与 `t_form_data_revision.data` 存同一份 payload；`createRevision` 从 `data` 复制（`DefinitionVersionRepository.java:152-169`），**只有 revision 有 checksum**，data 没有 | 同一事实两份存储，无约束保证同步；直接写 `data` 就出现"有值但无修订"的静默漂移 | 明确权威：把 `t_form_data.data` 定性为"当前值缓存"（加 checksum 或写路径唯一化），或读路径全面改走 `current_revision_id` 后停写 data |
| **直提（无流程）记录没有 revision**：`FormDataService.submit` 只插 `t_form_data`，`current_revision_id` 为 NULL；走引擎的记录 revision_no=1 | 同一张表两种数据形态，BI 视图不得不用 `latest` 兜底（V45），下游每处都要判空 | 二选一：直提也补一条 `SUBMITTED/INITIAL_SUBMIT` 修订（推荐，代价极小），或迁移明确"直提行永不参与版本解释" |
| `t_process_instance` 同时持有 `proc_def_id` + `process_def_version` + `process_snapshot`(JSONB) + `process_definition_version_id` + `current_node_instance_id` + `current_form_revision_id` + `current_node_id` | V1/V2 各一套引用，读取方需自行判断"以哪个为准"；快照与版本引用可能不一致 | V1 退役后收敛为**一套**：保留 `process_definition_version_id` + 修订引用 + 节点实例引用，`process_snapshot` 降级为"历史只读兼容列"并写清弃用版本 |
| `t_process_definition.nodes` / `edges`（V4 起弃用，仅 `DROP NOT NULL`）、以及 `ix_proc_nodes` / `ix_proc_edges` 两个 GIN 索引 | **死列 + 死索引**：每次写流程定义都要维护两个无人读的 GIN（写放大），实体类里已无对应字段 | 一条迁移删列删索引（与 V52 删 option row GIN 同一做法，需 EXPLAIN 佐证无人使用） |
| `t_form_data` 无 `(form_data_id)` 唯一性于实例侧：`t_process_instance.form_data_id` **没有 UNIQUE**，"一实例一份数据"只是应用约定 | V54 迁移不得不内嵌 `RAISE EXCEPTION` 运行时断言；未来任何 join 都可能放大行数 | 加 `UNIQUE (form_data_id)`（先做存量查重，V54 已给出查重 SQL，可直接复用） |

### B2 约束缺失（核心生命周期最弱） [新增，P0]

**现象**：新表普遍有 `ck_*`（`t_workflow_outbox`、`t_process_node_instance`、`t_node_participant`、`t_parallel_branch_state`、`t_form_data_revision`、`t_option_data_source*`…），但**最核心的四张表的 `status` 完全没有 CHECK**：

- `t_process_instance.status`（RUNNING/APPROVED/REJECTED/WITHDRAWN…）
- `t_task.status`（PENDING/APPROVED/REJECTED/SKIPPED/**CC**…）
- `t_form_definition.status`（DRAFT/PUBLISHED/DEPRECATED…）
- `t_form_data.status`（DRAFT/SUBMITTED）
- `t_mobile_file.status`（PROCESSING/READY/FAILED）

另外：`t_department` 无 `(company_id, name)` 唯一；`t_role_permission` 有 FK（好），但多态授权表（`t_form_resource_grant` / `t_option_data_source_grant` / `t_form_maintainer`）的 `subject_id` 无 FK（设计使然，但需配套清理）。

**影响**：脏状态只能靠应用层拦截，任何脚本、修数、历史遗留都可能写进非法值，而下游按字符串比较的 `switch`/`IN` 会静默走"其它"分支。

**优化方向**：一条迁移补齐核心状态列的 `CHECK`（先跑存量 distinct 值核对），并加"状态值集中定义"的测试（Java 常量 ↔ CHECK 列表对账）。多态授权表补**一致性巡检任务**（离职/删角色/删部门后残留授权），比硬加 FK 更实用。

### B3 生命周期表达方式过多 [新增，P1]

**现象**：同一实体存在多套"是否有效"的表达——`status`（DRAFT/PUBLISHED/DEPRECATED、ACTIVE/DISABLED）、`deleted SMALLINT`（V13）、`disabled_at`（V47）、`enabled`（`t_role`、`t_wecom_config`）、`company_inferred` 三态。

**影响**：查询与判权必须记住"哪张表用哪套"，漏一个就是可见性 bug；软删除用小整数而非时间戳，丢失"何时删、谁删"。

**优化方向**：写一份**每表生命周期字段口径表**（放在 `docs/PROJECT.md` 的"不变量"附近），并在新表统一为 `status` + `deleted_at TIMESTAMPTZ`；存量表不做形态改造（收益低），只补口径与测试。

### B4 索引与查询形态 [新增，P1]

| 现象 | 影响 | 优化方向 |
| --- | --- | --- |
| 列表主路径是 `company_id` + `created_at/started_at DESC` 排序，但只有单列索引（`ix_form_data_company`、`ix_form_data_form_def`、`ix_process_instance_company`、`idx_process_instance_status_started_at`） | 数据量上来后排序走外部排序；当前量级无感 | 按实际慢查询加复合索引：`(form_def_id, created_at DESC)`、`(company_id, created_at DESC)`、`(company_id, started_at DESC)` |
| 关键字检索全是 `ILIKE '%kw%'`（移动待办 6 列 OR、台账提交人子查询） | 无索引可用，纯扫描；大表直接劣化 | 规模内可接受；记录**触发条件**：单据 > 10 万或搜索 P95 超目标时，引 `pg_trgm` 或落到独立检索列 |
| 移动端待办用 `UNION ALL`（任务 + 抄送）+ 计算列 + `LIMIT/OFFSET` 深分页 | 无法用索引服务排序；深页性能线性下降 | 近中期用 keyset 分页（按 `created_at, id`）；A2 的抄送统一后可去掉 UNION |
| `t_form_definition.schema` / `t_form_data.data` 的 GIN（`jsonb_path_ops`）是否真被使用未验证 | 若无 `@>` / `@?` 表达式查询，就是纯写放大（V52 已对 option row 做过同样判断） | 用 `EXPLAIN` + `pg_stat_user_indexes.idx_scan` 取证，**无使用即删**（保留一件"取证后删除"的迁移，而不是留着"可能有用"） |
| 台账分页在 SQL 层过滤（正确），但每行带 `EXISTS` 子查询（`DataPermissionRules.FORM_DATA` 含回退分支） | 每行两次相关子查询，页越大越贵 | 中期可把 `started_dept_id` 冗余快照到 `t_form_data`（与 `company_id` 同样思路），把子查询降为列比较 |

### B5 归档与保留策略不完整 [新增，P1]

**现象**：只看到三处有明确保留机制——`t_audit_event`（在线 365 天 + 归档加密）、`t_idempotency_record`（`expires_at` 清理）、`t_external_auth_flow`（过 1 天删）。以下**无 TTL/清理**：`t_workflow_outbox`（DELIVERED 永久堆积）、`t_workflow_job`（完成/DEAD 作业）、`t_user_notification`（已读通知）、`t_cc_notification_batch`、`t_wecom_message_delivery`。

**影响**：单实例私有化部署通常没有 DBA 做人工清理，这些表会随运行年限单调增长（outbox 尤其，每条通知一行 JSONB）。

**优化方向**：一张**保留策略表**（表名 → 保留期 → 清理方式 → 归档去向）+ 一条定时清理任务（沿用现有 `@Scheduled` 模式，但要放到 A4 的独立池里）；`t_task_history` / `t_audit_event` 是审计资产，**只归档不清**（触发器已保护，保持）。

### B6 范式与 JSONB 使用 [评价]

- 整体是 3NF + **有意的快照冗余**（`company_id`、`started_dept_id`、`process_snapshot`、`form_definition_version`）——这是正确的：审批系统必须"按当时的事实解释当时的单子"。
- JSONB 承载表单 schema 与值，键是 nanoid、**无类型、无 CHECK**，唯一契约在应用层（`FieldTypeCatalog` + fixture）。**这是本项目最本质的技术风险点**，已被两条机制缓解：①版本快照 + 按记录版本解释；②四渠道 fixture 对拍。**建议维持"不把 JSONB 拍平成 EAV"**——EAV 会把这个复杂度放大十倍，且丢掉版本解释能力。
- 唯一要补的：**schema JSON 的顶层版本号**（当前 schema 是节点数组，没有"这份 schema 遵守哪一代字段语义"的标记），跨代解释只能靠字段类型推断。加一个可选 `schemaVersion` 字段并在保存时写入，成本极低，未来解释规则变更时可判代。

### B7 BI 只读视图直连库 [已知，有意接受]

视图（`v_form_ledger` 等 5 个）绕过三层鉴权、只授视图不授基表、刻意不暴露自由文本/账号——这个取舍站得住。要补的是**回归护栏**：①视图列集合与脱敏口径的断言测试（新增自由文本列即红）；②`bi-readonly-role.sql` 的授权脚本纳入 CI/发布清单（避免"视图加了列、角色忘了授"）；③文档里明确"视图是给全局可信账号的**投影**，不是第二套权限体系"。

### B8 迁移工程 [评价 + 一处风险]

- 只前滚、历史冻结、V31/V54 这类"带查重断言的破坏性回填"写得很克制——**保持**。
- 风险点：`V13`/`V14` 存在"修复本地库旧版本"的兼容迁移（注释写明是修历史漂移）。这说明**迁移集合曾经与本地库版本不一致**，也意味着新环境与老环境可能走过不同路径。建议补一份**"每张表当前有效定义"的对账产物**（可由测试从空库跑完 V1–V58 后 dump schema，与提交的期望文件比对），把"迁移终态"变成可核验事实，而不是靠人读 56 个文件推断。

---

## 4. 分层 / 模块 / 耦合 / 可维护性 / 可扩展性

### C1 分层与模块划分

- **包按功能划分是对的**，比按技术分层更适合这种业务。要留意的是 **`mobile` 包承担了共享能力**（文件、草稿、组织类型被桌面业务反向依赖），文档已立项（P5：能力上移到中立领域包）。ArchUnit 冻结了 **33 条**既有违规——冻结是正确策略，但**这 33 条应当有"只减不增 + 定期清零"的台账**，否则冻结层会变成永久债务区。
- **服务类规模**：`FormDefinitionService` 1403、`WecomService` 1313、`ProcessEngine` 1258、`WorkflowRuntimeV2` 1148、`OptionSourceService` 1044、`ProcessDefinitionService` 1018。**不按行数拆类**（文档 B5 已明确），但按职责拆是划算的：`FormDefinitionService` 至少含四类职责——schema 校验/可见性投影、发布与版本、查询分页、编号；其中"可见性/规范化投影"是跨端语义真源，最值得独立成 `FormSchemaSemantics`。

### C2 耦合

- 模块间是**直接调用**（无事件总线），对模块化单体正常；`outbox` 只服务通知投递，不要膨胀成通用事件总线。
- 真正的耦合风险是 **A2/A3**（读模型双实现、权限白名单），以及**跨端语义三份实现**（桌面 `registry`、移动 `schema`、后端 Java）。后者已有 fixture 与生成契约两道护栏，**不要急着做共享 npm 包**——文档 B5 的判断（"没有共同维护的纯 TS 运行库时不引 workspace"）继续成立。

### C3 可维护性

- **强项**：`docs/PROJECT.md`（不变量）+ `docs/DECISIONS.md`（决策）+ `HANDOFF.md`（交接 + 证据）+ 六健康指标 + "去掉修复即失败"的取证文化。这在同规模项目里是稀缺资产，**请继续保持并在 review 中保护它**。
- **弱项（本轮新发现，均为低成本）**：
  1. **55,221 行未注册路由的 Ant Design Pro 演示页入库**（`pages/dashboard/monitor` 占 49,624 行，含 1.2MB mock 地理数据；`pages/list`、`table-list`、`chatbot`、`form/advanced-form`、`profile/advanced`、`account/*` 同）——`config/routes.ts` 与 `pages/registry.ts` 均无引用，产品代码零 import。它们让 `biome lint`/`tsc`/构建扫描面变大，也让"这个页面到底是不是产品功能"难以判断。
  2. **硬编码凭据入库**：`backend/smoke-backend.bat`（跟踪中）含 `SPRING_DATASOURCE_PASSWORD=Tao@1234`、`JWT_SECRET=devsecret...`、`minioadmin` 与**本机绝对路径**；`backend/src/main/java/com/antflow/dev/BootstrapLocalDb.java`（**main 源码树**，会打进生产 JAR）默认口令同样是 `Tao@1234`；`application-local.yml` 的默认值同理。README 自己写着"不要提交任何密钥"。
  3. `HANDOFF.md` 的"已知遗留"清单已累积八轮、几十条，**缺退出条件与负责人**，容易从"交接"退化成"存档"。今天新增的 `docs/cleanup-candidates-2026-10-17.md` 正是应对，建议把该清单当作**唯一入口**并给每条加"退出条件"。
- **建议**：这三条都是**纯删除/替换类工作**（P0，零功能风险）：删演示页、清凭据（改环境变量 + 本地脚本不入库 + dev 引导类移出 main）、遗留清单加退出条件。

### C4 可扩展性（单实例假设清单）

明确记录当前架构的"单实例假设"与**各自的解除条件**，避免将来做水平扩展时踩坑：

| 假设 | 位置 | 解除方式 |
| --- | --- | --- |
| 进程内调度（无 leader 选举） | 14 个 `@Scheduled` | 多实例时加分布式锁 / 选主，或拆独立 worker |
| SSE 连接表在 JVM 内存 | `MobileEventController:36` | 粘性会话或 pub/sub 广播（A7） |
| 登录限流在内存 | Bucket4j | 换集中式存储 |
| outbox 投递占用共享调度池 | `WorkflowOutboxDispatcher:23` | A4：独立池 + SKIP LOCKED 批量 |
| 媒体转码在应用进程 | ffmpeg + 字体打进镜像 | A6：独立媒体服务（触发条件见上） |
| 事务内不做外部 IO | 已做到（媒体挪出事务） | **保持这条纪律**，它是单实例可用性的关键 |

**正面评价**：**"事务内不做外部 IO"这条纪律已经被严格遵守**（媒体上传/水印/对象存储写入都在事务外，删除对象放 `afterCommit`），这是本项目在并发正确性上最值钱的约定。

---

## 5. 优先级与落地建议

| 优先级 | 事项 | 影响面 | 代价 | 退出条件 |
| --- | --- | --- | --- | --- |
| **P0-1** | 删除未路由的演示页（55k 行）、清理硬编码凭据与本地脚本、遗留给退出条件 | 仓库卫生 / 安全自查 | 极低（纯删除替换） | 构建/测试全绿；`grep` 无口令；`biome lint` 扫描文件数下降 |
| **P0-2** | 数据权限 fail-open 收口（未登记即拒绝的检查 + 无主体走管理端读接口拒绝） | 安全边界 | 低 | 新增受控查询漏登记时 CI/启动红；负向用例齐全 |
| **P0-3** | 核心状态列补 `CHECK` + `t_process_instance.form_data_id` 加 `UNIQUE` + 删死列死索引（`nodes/edges` + 2 个 GIN） | 数据完整性 | 低（一条迁移 + 存量核对） | 迁移在真库跑通；状态常量与 CHECK 对账用例通过 |
| **P0-4** | 保留策略落地（outbox/job/notification/delivery 的 TTL 与清理） | 运维可持续 | 低 | 清理任务有指标、有 dry-run 日志 |
| **P1-1** | 双运行时收敛（runtimeV2 必需化 → RuntimePort → 按门禁删 V1） | 引擎可维护性 | 中（文档已估 12–20 人日） | 活跃 V1 实例清零；引擎无 `runtimeV2 == null` 分支 |
| **P1-2** | 待办读模型统一 + 抄送统一到 `t_cc_record` + 删除合成 id 协议 | 两端一致性 | 中 | 跨端一致性用例通过；契约里无 magic number |
| **P1-3** | outbox 投递移出调度线程；调度任务加耗时指标 | 稳定性 | 低-中 | 池饱和演练下 reaper/备份不被饿死 |
| **P1-4** | 实体/传输分离（受控字段 `@JsonIgnore`、新端点用 `record` + `@Valid`、展示字段改投影） | 契约质量 | 低（增量做） | 新端点不再返回实体；契约里出现必填项 |
| **P1-5** | 桌面端巨型页拆分 + 请求层按域收敛（对齐移动端结构） | 前端可维护性 | 中（随需求做） | 单文件 < 500 行；错误处理口径统一 |
| **P1-6** | 直提记录补修订行；`t_form_data.data` 权威性写清 | 数据一致性 | 低 | BI 视图不再需要"无修订兜底"分支 |
| **P2** | 复合索引 / keyset 分页 / pg_trgm（按慢查询触发）；独立媒体服务（按负载触发）；水平扩展三件套（A4/A7/限流） | 扩展性 | 视触发 | 每条都有明确触发条件，不到就不做 |
| **P2** | schema 加 `schemaVersion` 标记 | 语义演进 | 极低 | 新保存的 schema 均带版本号 |

**顺序建议**：P0 四项可以一周内合并（互不冲突，且都能配守护用例）；P1-1 与 P1-2 之间**不要并行**（都动流程语义/读路径，测试失败会难定位）；P1-3/P1-4/P1-5 可并行。

---

## 6. 明确不建议做（避免过度设计）

| 不做 | 理由 |
| --- | --- |
| 拆微服务 / 引 MQ / 引 Redis | 单实例私有化的内网审批，当前瓶颈是语义一致性与治理，不是吞吐；引入后每个新故障点都要运维兜底 |
| 上 BPMN 2.0 或换流程引擎 | 自研流程树已覆盖会签/或签/比例签/顺签/并行/加签/委托，且与版本快照、表单修订、审计深度耦合；换引擎等于重写业务 |
| 把 JSONB 拍平成 EAV 表 | 会把"字段语义演进"的复杂度放大十倍，并丢掉按版本解释历史的能力 |
| 事件溯源 / 通用规则引擎 | 历史表 + 审计 + outbox 已经够用；文档 B5 的触发条件（跨域重放、第二类用户规则）未出现前不动 |
| 重写 ORM 层（统一到 JPA 或全 JDBC） | 现状（MP 覆盖 CRUD + JDBC 覆盖复杂查询）是务实解；统一只会带来风险不带来收益 |
| 引 npm workspace 统一两端构建 | 同文档判断：唯一共享物是生成契约（已够），迁包不修任何已有 bug |
| 一次清完 `antd` 弃用项 | 文档已证明部分迁移会静默改坏行为（`showSearch` 对象形式是空操作）；按"语义等价 + 有用例兜"逐条走 |
| 为"好看"做的表结构规范化改造 | 现有快照冗余是**功能性需求**，不是设计缺陷 |

---

## 7. 核验索引（本轮取证位置）

- 双运行时：`backend/src/main/java/com/antflow/engine/ProcessEngine.java:140`（`setEngineVersion(2)`）、同文件 `runtimeV2` 引用 50 处 / `runtimeV2 != null|== null` 分支 25 处；`engine/WorkflowRuntimeV2.java`
- 抄送双模型与合成 id：`mobile/workflow/MobileWorkflowMapper.java:90/319/378`；`mobile/workflow/MobileWorkflowService.java:55/317`；`engine/handler/CcHandler.java:49`
- 行级权限默认放行：`authz/DataPermissionPolicyHandler.java:31-34`、`authz/DataPermissionRules.java:70`（仅 2 条规则）；对照 `authz/InstanceScopeSql.java:26-58`（fail-closed 的正确写法）
- 调度池：`backend/src/main/resources/application.yml:32-39`；`notify/WorkflowOutboxDispatcher.java:23/48`；`@Scheduled` 共 14 处
- 可选注入：`@Autowired(required = false)` 15 处（`ProcessEngine`、`FormDefinitionService` 等）
- SSE：`mobile/workflow/MobileEventController.java:36`
- 死列/死索引：`db/migration/V4__process_tree.sql:3-5`、`V3__indexes.sql:4-5`；`process/ProcessDefinition.java` 无 `nodes/edges` 字段；全仓无 `getNodes()/setNodes()`
- 双份数据：`process/DefinitionVersionRepository.java:152-169`（`createRevision` 从 `data` 复制并回写 `current_revision_id`）；`form/runtime/FormDataService.java:126`（直提只插 `t_form_data`，无修订）
- 缺 UNIQUE：`db/migration/V54__company_attribution_snapshot.sql` 头部注释（`form_data_id` 无 UNIQUE，迁移内需 `RAISE EXCEPTION` 查重）
- 状态列无 CHECK：`V1__init.sql:91-115` 等；对照 `V31__workflow_v2_foundation.sql:178-181/208-210`
- 演示代码：`frontend/src/pages/dashboard/monitor/*`（49,624 行 / 1.2MB mock）、`pages/list`、`pages/table-list`、`pages/chatbot`、`pages/form/advanced-form`、`pages/profile/advanced`、`pages/account/*`；`frontend/config/routes.ts` 与 `frontend/src/pages/registry.ts` 均无引用
- 凭据入库：`backend/smoke-backend.bat`（跟踪中）、`backend/src/main/java/com/antflow/dev/BootstrapLocalDb.java`、`backend/src/main/resources/application-local.yml:3/12/21`
- 巨型组件：`frontend/src/pages/approval/FormManagementWizard.tsx`(1250)、`pages/proc/Detail.tsx`(965)、`pages/org/Contacts.tsx`(750)
- 保留策略缺口：全仓 `DELETE FROM` 列表（`IdempotencyRecordMapper:50`、`OidcService:224`、`AuditArchiveService`）之外无 outbox/job/notification 清理
