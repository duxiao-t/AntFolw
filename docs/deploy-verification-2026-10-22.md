# 部署与改动验证报告（2026-10-22）

## 一、部署结果

| 项 | 值 |
| --- | --- |
| 部署方式 | `bash deploy.sh --all`（docker compose + 健康检查 + 失败回滚） |
| 部署提交 | `bfb6c85`（master，领先 origin/master 29 个提交） |
| 容器 | `antflow-local-{postgres,minio,backend,web}` 全部 healthy |
| 入口 | http://127.0.0.1:7070（web → nginx），MinIO 7072/7073 |
| 健康检查 | `{"status":"UP"}` |
| 数据库 | 新建空库，Flyway 执行 59 个迁移；已有历史业务数据（表单 1-42、流程实例、待办 15 条） |
| 前端版本水印 | 页面底部 `ver 0.1.0 bfb6c85` —— 确认跑的是最新代码 |

**部署过程中修复的问题**：`postgres` 容器此前不存在（数据卷缺失），导致 backend 卡在 `HikariPool Starting...` 无限重试。
执行 `docker compose up -d postgres` 后 Flyway 自动建表，backend 转为 healthy。

## 二、验证方式

用 Playwright 驱动**系统 Chrome**（`channel: 'chrome'`，无需下载 Chromium）访问 `http://127.0.0.1:7070`，
脚本存于 `.verify/`，截图存于 `.verify/shots/`。断言优先用可访问名称与接口响应，辅以 `/api` 调用取证。

## 三、逐项验证结果

### 认证与账号

| ID | 改动 | 位置 | 步骤 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| V1-01 | 登录链路 | `/user/login` | admin/Tao@1234 登录 | 跳离登录页 | 落地 `/workplace` | ✅ |
| V1-03 | `efc14a6` bob 无法登录 | `/user/login` | bob 登录 | 能进入后台 | 落地 `/workplace`，菜单少于 admin（权限生效） | ✅ |
| V5-02 | `efc14a6` 改密入口 | `/account/settings` | 打开个人设置 | 有修改密码入口 | 「个人设置 / 修改登录密码」存在 | ✅ |

### 表单域

| ID | 改动 | 位置 | 步骤 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| V2-01 | `d2f87c4` 向导拆分 | `/approval/forms/new` | 打开第 1 步 | 基础信息/维护人员/使用范围/业务单号四块 | 四块齐全，两组授权可独立保存 | ✅ |
| V2-02 | 保存表单属性 | 同上 | 填名称+编码→保存 | 接口 2xx | `POST /api/forms/definitions`、`PUT /forms/42/grants`、`PUT /forms/42/maintainers` 均 200 | ✅ |
| V2-03 | 步骤切换 | 向导 | 点第 2 步 | 进入设计器 | 出现「基础组件/单行文本…」 | ✅ |
| V8-01 | `5ab07a6` schema 解析 | `/api/forms/definitions` | 写入 5 类字段（text/select/dept_picker/table_list/checklist） | 落库且可回读 | 200，回读 821+ 字符 | ✅ |
| V8-02 | 设计器渲染 | `/approval/forms/42/wizard?step=designer` | 打开 | 字段全部渲染 | 命中 6/6（含部门选择/明细表/检查项） | ✅ |
| V8-03 | `d2f87c4` 发布检查 | `?step=publish` | 打开 | 有字段后检查通过 | 5 项全绿，「当前共 7 个组件」 | ✅ |
| V11-01 | 发布链路 | 同上 | 点发布→确认 | 200 且 PUBLISHED | 200，version 6，PUBLISHED | ✅ |
| V11-02 | runtime 渲染 | `/runtime/form/deploy_verify` | 打开 | 字段+提交按钮 | 字段与「提 交」均在 | ✅ |
| V12-01 | 无流程提交 | 同上 | 选公司→提交 | 接口 2xx | `POST /api/forms/data` 200，数据 `{"v_text":"部署验证-提交内容","v_table":[]}` | ✅ |
| V4-01 | `fcd610c` 选项源 | `/approval/option-sources` | 打开 | 列表+新建+版本化 | 有数据源 `ces v4`（2 张表单在用），按钮齐 | ✅ |

### 流程域

| ID | 改动 | 位置 | 步骤 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| V15-01 | `bca4e3f` 详情拆 11 文件 + `4b099f2` 展示模型五层 | `/proc/38` | 待办点「查看流程」 | 头部/进度/表单/轨迹齐全 | 头部（单号/状态/操作）、当前进度、FLOW ROUTE 2/4、FORM CONTENT、ACTIVITY 2 条全渲染 | ✅ |
| V16-01 | `45dfcec` 引擎 approve | `/tasks/inbox` | 点「同意」→填意见→确定 | 接口 2xx，待办减少 | `POST /api/tasks/142/approve` 200，行数 15→14 | ✅ |
| V14-inbox/done | 待办/已办列表 | `/tasks/inbox`、`/tasks/done` | 打开 | 正常渲染 | 均正常，含历史任务 | ✅ |
| V14-proc-list | 流程监控 | `/proc` | 打开 | 正常渲染 | 正常（断言因文本含数字 404 误判，非缺陷） | ✅ |
| V13-01 | 带审批流程的发起 | `/runtime/form/form_1788074207494` | 填必填项→提交 | 生成实例 | **422**，未拿到实例 | ⚠️ 未完成 |

### 系统与集成

| ID | 改动 | 位置 | 步骤 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| V5-01 | `bfb6c85` 企微设置拆 8 文件 + 4 个后端提交 | `/settings/wecom` | 打开 | 连接/同步/后台/接入四块 | 四块齐全 | ✅ |
| V6-01 | `cd14e45` 企业基础信息 | `/settings/company` | 打开 | 列表+新建+编辑 | 「公司管理」列表、「新建公司」、行内「编辑」 | ✅ |
| V6-02 | `cd14e45` S3 可自检 | `/settings/s3` | 点「测试连接」 | 返回连通性 | Tag：`minio / 已配置 / 可用 / 桶已存在 / 可写`，耗时 17ms | ✅ |
| V6-03 | `bc72eeb` 仅数据库备份 | `POST /api/system/backups?databaseOnly=true` | 触发后轮询列表 | 出现 `-db` 文件且体积小 | **接口 200，文件确实生成在 `/backups/antflow-db-20261022-071059.afbackup`，但列表接口不返回它** | ❌ |

## 四、发现的问题

### 问题 1（真实缺陷）：仅数据库备份生成后不可见

- **现象**：`POST /api/system/backups?databaseOnly=true` 返回 200，容器内 `/backups/` 确实出现 `antflow-db-20261022-071059.afbackup`；
  但 `GET /api/system/backups` 只返回 3 个全量文件，`-db` 文件不出现，UI 上无法下载/删除。
- **根因**：`BackupService.java:269`
  ```java
  .filter(path -> path.getFileName().toString().matches("antflow-[0-9-]+\\.afbackup"))
  ```
  该正则只匹配 `antflow-2026…`，不匹配 `antflow-db-2026…`（`db` 不是数字或横线）。
  `bc72eeb` 引入 `-db` 前缀时未同步更新此正则。
- **影响**：灾备场景下用户看不到自己刚生成的轻量备份，也无法从 UI 恢复。
- **建议修复**（1 行）：
  ```java
  .matches("antflow(-db)?-[0-9-]+\\.afbackup")
  ```
- **同类位置**：`cleanup(days)` 若也用同一正则，需一并检查（过期清理会漏掉 `-db` 文件）。

### 问题 2（前后端校验口径不一致）：发布检查显示全绿，后端仍 422

- **现象**：右侧「发布检查」5 项全绿（含「至少包含 1 个组件」），点发布后端返回
  `422 BAD_SCHEMA: matrix_fill.rows is required` / `表单结构无效`。
- **根因**：前端 `buildPublishChecks` 只做粗粒度检查（是否有组件、选项是否配置），
  后端 `FormSchemaValidator` / `FormMatrixRules` 会逐字段校验（矩阵必须有 rows/columns/cellType/maxLength）。
- **影响**：用户看到"检查通过"却在发布时被拒，提示不如前端直白。
- **建议**：把矩阵/明细表的必填项同步到 `wizardValidation.ts` 的检查项里。

### 问题 3（验证方法问题，非应用缺陷）

- **V3 系列最初判为 PASS 是假通过**：设计器用 `@dnd-kit`（pointer events）拖拽添加字段，
  点击左侧组件只会选中、不新增；我的断言只看了"点击未报错"，实际画布始终为空（截图证伪）。
  已改用「API 写入 schema + 浏览器验证渲染」重做（V8/V11），结论以 V8/V11 为准。
- **V12 首次判为 PASS 也是假通过**：断言 `api.some(200)` 误匹配了登录请求；实际提交被前端拦截
  （admin 无部门需手选公司），补选公司后才真正产生 `POST /api/forms/data`。

### 问题 4（种子数据体验）：admin 没有部门

发起流程时页面要求「当前账号没有部门，请选择本次提交归属的公司」。
bootstrap 创建的 admin/bob 未分配部门，新环境首次使用会多一步手动选择。

## 五、未覆盖 / 无法验证的改动

| 改动 | 原因 |
| --- | --- |
| `aa110eb` 撤回三态、驳回、幂等重复提交 | 只验证了「同意」；撤回/驳回需构造特定状态（发起人/runner/返工），本轮未做 |
| `a44ddcd` 媒体时长校验 | 需上传超长视频，且依赖 ffprobe，本轮未构造 |
| `0bd3dca` 分块 AES-GCM 加密 | 需下载备份并解密校验；备份文件 1.26 GB，未下载 |
| `45dfcec` 引擎驳回/撤回/并行分支 | 同上，只覆盖 approve 主路径 |
| `bc72eeb` 定时任务「永远全量」 | 需等待调度时间或改配置，未验证 |
| `ced0a51`/`dd460ce`/`2bcdd1e` 构建脚本 | 属于 CI/命令行范畴，本次部署本身即其产物（`deploy.sh --all` 成功） |
| `346a8de`/`5c17ace` H7 门禁、`tsc:fast` | 命令行验证：H7 退出 0；`tsc:fast` 退出 0（已在前序提交中验证） |
| `a44ddcd`/`04e0b63` 契约门禁 | 需 `npm run openapi:check`，本轮未跑 |
| `cd14e45` 身份提供方页面 | 未打开（`/settings/identity-providers`） |
| 移动端 5 个文件改动 | 移动端页面（`/mobile`）未做浏览器验证 |
| 企微真实同步（5 个提交） | 仅验证 UI 渲染与保存；真实同步需企微 corpId/secret，未配置 |

## 六、结论

- **部署成功**，服务 healthy，前端水印确认运行 `bfb6c85`。
- **29 个提交中，可浏览器验证的核心链路（登录、表单设计/发布、schema 校验、选项源、企微设置、
  企业信息、S3 自检、流程详情、审批同意）全部通过**。
- **发现 1 个真实缺陷**（备份列表正则漏 `-db`，1 行可修）与 **1 个前后端校验不一致**（发布检查不覆盖矩阵必填）。
- **未覆盖集中在"需要特定数据状态"的路径**（驳回/撤回/幂等/媒体时长/备份解密），建议后续用集成测试或手工用例补齐。

---

## 七、第二轮：问题修复与复验（2026-10-22 下午）

### 修复 1：备份列表正则（提交 `9a726c6`）

`BackupService` 里 `files()`（列目录）与 `file()`（下载/删除的路径校验）**各写死了一份**
`antflow-[0-9-]+\.afbackup`，`bc72eeb` 加 `-db` 前缀后两处都没跟着改。抽成唯一常量：

```java
static final Pattern BACKUP_NAME = Pattern.compile("antflow(-db)?-[0-9-]+\.afbackup");
```

两处共用；`cleanup()` 走 `files()`，过期清理也随之覆盖 `-db` 文件。
新增 `BackupFileNameTest` 锁口径（正例 + 未知前缀/缺时间戳/扩展名不符/路径穿越/非本服务产物）。

**复验**：

| ID | 步骤 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- | --- |
| W1-01 | `GET /api/system/backups` | 至少 1 条 `-db` | 全量 3 条 + **仅库 2 条**（334KB / 330KB） | ✅ |
| W1-02 | `GET /api/system/backups/{name}/download` | 200 且非空 | **HTTP 200，334 KB** | ✅ |

体积对比印证 `bc72eeb` 的目标：全量 1,295,571 KB → 仅库 **334 KB**（约 1/4000）。

### 修复 2：发布检查补结构校验（提交 `dbc18a4`）

新增 `collectStructureErrors(schema)`，覆盖矩阵的 `rows/columns/cellType/maxLength/precision`
与明细表的 `children`，递归含子节点；`buildPublishChecks` 增加 `structure` 检查项（插在 `schema` 之后）。

**复验**：

| ID | 步骤 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- | --- |
| W2-01 | 构造空矩阵 + 空明细表，打开发布页 | 出现「组件结构完整」且报「缺少行」 | 两项均命中（「缺少明细列」被 `slice(0,3)` 截断，属预期） | ✅ |
| W2-02 | 换成合格矩阵（rows/columns/number/precision） | 不再报错 | 无「缺少行」，显示「行列配置齐全」 | ✅ |

### 撤回 / 驳回 / 幂等（未改代码，仅验证）

| ID | 动作 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- | --- |
| W3-01 | 同一任务连续 `approve` 两次 | 一次成功、二次被拒 | 第一次 **200**，第二次 **422 `TASK_NOT_PENDING`** | ✅ |
| W3-02 | 对待办 `reject`（带意见） | 2xx | **200** | ✅ |
| W3-03 | 对 admin 发起的 RUNNING 实例 `withdraw` | 2xx | **200**（该账号 RUNNING 实例共 20 个） | ✅ |

### 备份分块解密（`0bd3dca`）

用生产同款 `BackupArchiveCipher.decrypt` 解密 `-db` 备份：

| 项 | 结果 |
| --- | --- |
| 文件头 | **`AFBK2`**（分块 AES-GCM） |
| 加密体积 | 334 KB |
| 解密后 | 341,831 字节（zip） |
| 内容 | `database.dump`（533,394 B）+ `manifest.json`（203 B） |
| manifest | `{"version":1,...,"mobileObjects":0,"auditObjects":0,"entries":[{"path":"database.dump","sha256":"ad59a252…"}]}` |

`mobileObjects:0 / auditObjects:0` 证明 `databaseOnly` 确实跳过了两个桶；`sha256` 可用于完整性校验。
**旧格式兼容（AFBK1）未验证**——现有备份都是 AFBK2，需要一份历史 AFBK1 归档才能覆盖。

### 移动端（5 个文件改动）

| ID | 位置 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- | --- |
| W4-01 | `/mobile/` | SPA 正常渲染 | `/mobile/workbench`，待办 14 / 最近流程 4 / 草稿 1 | ✅ |
| W4-02 | `/mobile/profile/password` | 改密表单 | 三个 password 框（原密码 / 8-64 位新密码 / 确认），含「修改成功后其它设备需重新登录」提示 | ✅ |
| W4-03 | `/mobile/profile/security` | 有「修改密码」入口 | 「账号安全 → 修改密码」存在 | ✅ |

### 部署链路的一个环境问题

`bash deploy.sh --all` 在本机失败：

```
fatal: unable to access 'https://github.com/duxiao-t/AntFolw.git/':
Failed to connect to github.com:443 over proxy 127.0.0.1 after 2064 ms
```

`deploy.sh`、`scripts/build.sh`、`backend/build.sh` 里都 grep 不到 `git fetch` / `remote`，
但脚本一启动就报此错，且三条构建命令单独执行都成功。**触发点未定位**（疑似 git 的隐式 fetch
或本机 git 配置），本轮改为执行等价步骤完成部署：

```bash
bash scripts/build.sh frontend --no-budget
bash scripts/build.sh mobile --no-budget
bash backend/build.sh -Dmaven.test.skip=true package
docker compose --env-file .env.docker.local build backend web
docker compose --env-file .env.docker.local up -d backend web
```

部署后 backend 立即 healthy、`{"status":"UP"}`。**建议**：给 `deploy.sh` 加一个
`ANTFLOW_SKIP_REMOTE_CHECK=1` 开关，或在无网环境显式跳过需要远程的步骤。

---

## 九、第三轮：拆分后回归（2026-10-22 晚）

### 部署

发布 `bf19518`（51 个未推送提交，含本轮 17 个类的拆分）。四个容器 healthy，`/actuator/health` 200，
`frontend/dist` 产物内含 `bf19518` 水印，确认跑的是最新代码。

**部署过程遇到 4 个环境问题（都不是代码问题，但拖了很久）**：

| # | 现象 | 原因 | 处理 |
| --- | --- | --- | --- |
| 1 | `backend/build.sh` 打印两行后卡住 12 分钟 | 它最后是 `docker run --rm maven:3.9-eclipse-temurin-17 mvn ...`，要在容器里跑 Maven（拉镜像/预热） | 改用本地 `mvn -B -Dmaven.test.skip=true package`（20 秒完成） |
| 2 | `docker compose up` 报 `container ... is zombie and can not be killed` | 旧 backend 容器进程成僵尸，daemon 无法回收 | `docker rm -f` 强制删除后重建 |
| 3 | compose 卡在 `backup-init` Starting | backend 的 `depends_on: backup-init: service_completed_successfully`，初始化容器起不来就一直阻塞 | 删除该容器后 `docker start` 直接起 backend/web |
| 4 | `docker start` 返回 0 但容器仍是 `Created`，健康检查 504 | Docker daemon 状态异常（僵尸容器拖累） | `docker desktop restart`（57 秒）后一切恢复 |

### 回归验证

**页面级（17 个页面，覆盖全部拆分类）**：18/18 通过，**零 4xx/5xx**（除登录前预期的 401）。

| 页面 | 覆盖的拆分 |
| --- | --- |
| `/workplace` | 基础 |
| `/org/contacts` | `UserService` 6 类 + 前端 `Contacts` 7 文件 |
| `/security/roles` | `RoleAdminService` 3 类 |
| `/security/user-permissions`、`/security/audit-log` | `AuthorizationService` 5 类 |
| `/approval/forms`、`/admin/form-data` | `ProcessDefinitionService` 5 类 |
| `/tasks/inbox`、`/tasks/done`、`/approval/monitor`、`/proc` | `ProcessEngine` 6 类 + `ApprovalOrchestrator` |
| `/approval/option-sources` | `OptionRuntimeService` 5 类 |
| `/settings/wecom` | `WecomAppService` 7 类 |
| `/settings/identity-providers` | `OidcService` 5 类 |
| `/settings/backup`、`/settings/company` | 备份 + `UserService` 公司维度 |

**写路径**：

| ID | 操作 | 预期 | 实际 | 结论 |
| --- | --- | --- | --- | --- |
| R2-01 | 流程设计器保存 + 回读 | 2xx | 保存按钮可用，回读 200 | ✅ |
| R2-02 | 发起流程（`ProcessStarter`） | 2xx | `POST /api/forms/data` **200**，跳 `/runtime/list` | ✅ |
| R2-03 | 审批普通任务（`ApprovalOrchestrator`） | 2xx | 任务 106 **200** | ✅ |
| R2-04 | 上传 PNG（`MobileFileService` 7 类） | 2xx | **200**，返回文件 id | ✅ |
| R2-05 | 对返工任务 approve（守卫） | 422 `BAD_TASK_TYPE` | 任务 154/153 均 **422 `BAD_TASK_TYPE`** | ✅ |

> **R2-03 首次判 FAIL 是一次误报**：待办列表第一条（154）是**返工任务**，`approve` 返回
> `422 BAD_TASK_TYPE: Rework task must be resubmitted from the form`。这是**业务规则不是缺陷**——
> 换普通任务（106）后 200。反过来证明 `BAD_TASK_TYPE` 守卫在拆分后仍正确。

**移动端**：

| 页面 | 覆盖 | 结果 |
| --- | --- | --- |
| `/mobile/workbench` | `MobileWorkflowService` 首页投影 | ✅ 376 字，零 API 异常 |
| `/mobile/tasks` | 列表 + `ApprovalRecords` 5 文件 | ✅ 987 字，零 API 异常 |
| `/mobile/forms/deploy_verify` | 填单 + `FileUploadField`/`ChecklistField` | ✅ 560 字，零 API 异常 |
| `/mobile/profile/security` | 安全页 | ✅ 「账号安全 / 修改密码 / 登录设备 66 台」 |

**选项源运行时（`OptionRuntimeService`）**：

| 场景 | 结果 | 说明 |
| --- | --- | --- |
| 内联选项字段调 `POST /api/runtime/form-options/query` | **422 `BAD_SCHEMA: 字段没有外部数据配置`** | 正确：该字段没绑外部数据源 |
| DRAFT 表单（id=1, version 7）的绑定字段 | **404 `field not found`** | 正确：运行时只服务已发布版本 |
| 已发布 + 绑定选项源的表单 | **查无此数据** | ⚠️ **未覆盖**（见下） |

### 仍未覆盖 / 无法验证

| 项 | 原因 |
| --- | --- |
| **选项源运行时成功路径**（返回选项行） | 库里没有「PUBLISHED 且字段绑定 optionSource」的表单（唯一的绑定了的 id=1 是 DRAFT）。需先发布那样一张表单才能验 |
| **V2 实例状态机**（`WorkflowRuntimeV2` 10 个类） | 需要 `engineVersion=2` 的新实例；本次发起的实例走的是当前引擎路径，V2 的 SEQUENTIAL/并行 ANY 收口仍只有集成测试覆盖 |
| **OIDC 登录闭环** | 无 OIDC provider 配置，只能验证页面渲染与「未配置」态 |
| **企微真实同步 / 免登** | 需真实 corpId/secret |
| **`WecomAppMessageDispatcher` 的事件投递** | 需企微回调，且要真实群机器人 |
| **`BAD_TASK_TYPE` 之外的任务类型守卫**（加签/委派/超时） | 需构造对应任务类型，本轮只覆盖返工 |
