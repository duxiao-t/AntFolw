# P4a 上线 runbook（公司归属快照 / V54）

> **owner:** 待定（谁负责历史归属 `company_id IS NULL` 的后续确认 + 上线验收）
> **window:** 待定（维护窗口时间）
> **draft handler:** 待定（谁负责 §6.2 的历史草稿逐条回填）
>
> 这三项见 `HANDOFF.md` 顶部"仍差"。**未填不影响本 runbook 可用**，但上线前必须落名。

本 runbook 只覆盖 **V54 + 新应用一起上线**。它**不改读权限**——公司隔离是 P4 后续的事。

> **读之前先记住一条**：`company_id` / `company_inferred` **由 V54 才创建**（`V54:20-23`）。
> 所以**窗口前的任何 SQL 都不许查这两列**，会直接报列不存在。§1 全是旧 schema 上的查询，
> 查这两列的都在 §4（上线后）。

---

## 0. 先理解一件事：这不是滚动发布，是停机重启

`HANDOFF.md` 早先写的"迁移先上、应用后上"**在本仓做不到**，别照它试。实测依据：

- **单实例**：`compose.yaml:41-86` 只有一个 `backend` 服务，全仓 grep 不到
  `replicas` / `--scale` / `deploy:`；`infra/docker/nginx.conf:24-52` 是
  `proxy_pass http://backend:8080`，**没有 `upstream` 块**。
- **Flyway 在应用容器启动时跑**（`application.yml:26-29`，`enabled: true`）；
  `deploy.sh` / `Dockerfile*` / CI 里**都没有**独立迁移步骤（grep `flyway` 零命中）。

⇒ `docker compose up -d --build backend` 对单副本就是**先停后起**，新旧版本**不可能同时在线**，
所以"旧实例长期混跑、写出没有 `company_id` 的新记录"这个风险在本仓**不存在**——
不是被缓解了，而是原始条件不成立。迁移与应用**必然同时生效**。

---

## 1. 上线前：只读检查（**窗口外做**，结果只用于判断风险，不进文档）

**这一节的每条都只用 V54 之前就存在的列。** 用得上的是：
`t_process_instance.started_dept_id / started_at / form_data_id / proc_def_id`、
`t_department.company_id`、`t_form_data.id`。

```sql
-- 1) 迁移后会**仍是 NULL** 的行数（回填只按 started_dept_id 映射，所以它就是上界）。
--    t_department.company_id 是 NOT NULL，所以"有部门但部门没公司"这种情况不存在。
SELECT count(*) AS will_stay_null
FROM t_process_instance WHERE started_dept_id IS NULL;

-- 2) 今天的公司分布 —— 用**旧 schema** 的 JOIN 看（窗口前没有 company_id 列可查）。
--    这是给业务解释"回填大概会怎么落"用的，不是决策依据。
SELECT d.company_id, count(*) AS instances
FROM t_process_instance pi JOIN t_department d ON d.id = pi.started_dept_id
GROUP BY d.company_id ORDER BY d.company_id;

-- 3) **窗口前唯一必须看的一条**：pre-flight 的只读等价物（对应 V54:30-32）。
--    空集 ⇒ V54 不会因它中止。非空 ⇒ 停，先人工处理那几份 form_data，别往下走。
SELECT form_data_id, count(*) FROM t_process_instance
GROUP BY form_data_id HAVING count(*) > 1;
```

写法安全性：`form_data_id` 在 `V1__init.sql:94` 是 `NOT NULL REFERENCES`，
所以 `GROUP BY` 不会把 NULL 归成一组误报。

### 为什么这条重复检查值得单独跑（它的价值已被重新定位）

V54 失败**损坏不了任何东西**（见 §5），所以它**不是**用来"防损坏"的。
它的价值是**别在停机窗口里才发现**——把发现时机从窗口内前移到窗口前。
人工处理一份重复挂载的 `form_data` 要多久，就是省下来的窗口时间。

---

## 2. 备份与镜像留存（**先把能回退的东西存下来**）

§2.1–§2.3 是**数据库备份**：它只在"迁移**成功**、随后才发现业务问题"这种场景下有用；
"迁移失败"不需要它（见 §5）。§2.4 是**旧镜像留存**，它是 §5.3「回退旧镜像」那一格能成立的前提。
两件事都必须在 §3 动任何东西**之前**做完。

### 2.1 做备份

```bash
# 在宿主上。容器名/库名/用户以实际为准（本机栈是 antflow-local-postgres-1 / antflow / antflow）。
docker exec antflow-local-postgres-1 \
  pg_dump -U antflow -d antflow -Fc -f /tmp/pre-v54.dump
docker cp antflow-local-postgres-1:/tmp/pre-v54.dump ./pre-v54-$(date +%Y%m%d%H%M).dump
sha256sum ./pre-v54-*.dump | tee ./pre-v54-*.dump.sha256
ls -l ./pre-v54-*.dump
```

**产物位置**：仓库之外的一个目录（**别放进仓库**，`pg_dump` 会带出真实业务数据）。
**记录**：文件名、大小、`sha256`。这三样写进发布记录（不是写进本文件）。

### 2.2 校验（**必做，否则等于没备份**）

`pg_dump` 退出码为 0 也证明不了它能恢复。校验方法就是**恢复到一个临时库看一眼**：

```bash
docker exec antflow-local-postgres-1 createdb -U antflow pre_v54_verify
docker cp ./pre-v54-XXXX.dump antflow-local-postgres-1:/tmp/verify.dump
docker exec antflow-local-postgres-1 \
  pg_restore -U antflow -d pre_v54_verify --no-owner --no-privileges /tmp/verify.dump
docker exec antflow-local-postgres-1 \
  psql -U antflow -d pre_v54_verify -c 'SELECT count(*) FROM t_process_instance;'
docker exec antflow-local-postgres-1 dropdb -U antflow pre_v54_verify
```

`t_process_instance` 的行数与生产对得上，这份备份才算数。**这一步在窗口外做。**

### 2.3 真要用它恢复（**完整停机流程**）

```bash
docker compose --env-file .env stop web backend          # 先停应用，避免边恢复边写
docker cp ./pre-v54-XXXX.dump antflow-local-postgres-1:/tmp/restore.dump
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" antflow-local-postgres-1 \
  pg_restore --clean --if-exists --no-owner --no-privileges \
  --username antflow --dbname antflow /tmp/restore.dump
docker compose --env-file .env up -d backend web
```

`--clean --if-exists` 会先删再建，**这是破坏性的**，所以必须先停应用。
恢复后 `flyway_schema_history` 也回到备份时的状态（= 没有 V54），下次启动会重新跑 V54。

> **另一条路**：`backend/backup/restore-backup.sh` 处理的是**应用自己产出的加密
> `.afbackup` 格式**，不是 `pg_dump` 的产物。它需要 `BACKUP_ENCRYPTION_SECRET`，
> 且脚本里用了 `jq`（本机 Git Bash **没有** `jq`）。它的流程是
> `stop web backend` → `pg_restore --clean --if-exists` → `up`。
> 两条路选一条即可，别混用格式。

### 2.4 保存当前镜像（**顺序写死：tag 必须早于 build**）

**为什么必须做**：`compose.yaml` 的 backend/web **只有 `build:`、没有 `image:`** ⇒ 每次
`docker compose up -d --build` 都覆盖同一个 `antflow-local-backend:latest`；`deploy.sh` 里
**没有** `docker tag` / `docker save` / 推镜像。所以**不先存，就没有"上一个镜像"可回退**，
§5.3 那张表里的"回退旧镜像"当场不成立。

**顺序**（`--build` 会覆盖 `latest`，**先 build 再 tag 存下来的是新镜像**）：

```bash
# 1) 先看清现在跑的是哪一份
docker compose --env-file .env.docker.local images backend web

# 2) 记录 ID —— 写进发布记录，第 4 步要核对
docker image inspect --format '{{.Id}}' antflow-local-backend:latest

# 3) 此刻还没 build，tag 到的才是旧镜像
docker tag antflow-local-backend:latest antflow-local-backend:pre-v54
docker tag antflow-local-web:latest     antflow-local-web:pre-v54

# 4) 校验 tag 指向的确实是第 2 步记下的那个 ID；不一致就停，别往下走
docker image inspect --format '{{.Id}}' antflow-local-backend:pre-v54

# 5) 到这里才可以 build
```

`docker save` 是**可选加强**（把镜像落成 tar 进备份目录，防 `docker image prune`），
**不是 tag 的替代**。tag 与 image ID 都要进发布记录。

---

## 3. 上线步骤

1. **镜像在窗口前就构建好**：`docker compose --env-file .env.docker.local build backend web`。
   把编译时间移出窗口。**但 build 之前必须先做完 §2.4 的 tag**，否则就存不到旧镜像了。
2. **停旧容器**。
3. **起新版本**：`docker compose --env-file .env.docker.local up -d backend web`
   （V54 随启动生效）。
4. **健康检查**：`http://127.0.0.1:7070/actuator/health`（`deploy.sh:192-193` 用同一个探针）。
   起不来的处置见 §5。

### 3.1 窗口时长要按**整个 Flyway migrate** 估，不只是两个 UPDATE

V54 在窗口里要干的全部事情（`V54:20-23,42-55,61-64`）：

| 步骤 | 开销 / 锁 |
| --- | --- |
| 4 × `ALTER TABLE ADD COLUMN`（2 表各 2 列） | 各取 **ACCESS EXCLUSIVE**（阻塞一切读写）。`company_inferred` 带 `DEFAULT false`，PG 11+ 不重写表；`company_id` 带 `REFERENCES`，新列全 NULL 所以外键校验是空的，但 `ALTER` 本身的锁要等**长事务**让路 |
| `DO $$ … RAISE … $$`（pre-flight） | 一次 `GROUP BY` 全表扫描 |
| `UPDATE t_process_instance … FROM t_department` | 全表回填 + 行锁 |
| `UPDATE t_form_data … FROM t_process_instance` | 同上 |
| 2 × `CREATE INDEX` | **不是 `CONCURRENTLY`** → 取 SHARE 锁，**建索引期间该表无法写入**。大表上这一步常比回填更贵 |

⇒ **掐表要掐完整的 `Flyway migrate`**（不是单跑两条 UPDATE）。**本文件不给估算值。**

### 3.2 预演：用仓内工具，在副本库上量

工具是 `scripts/rehearse-v54.sh` + `scripts/rehearse/V54Rehearsal.java`（走**项目自己那份 Flyway**，
量总耗时 + 采样 `pg_locks`/`pg_stat_activity`，并在 migrate 前打印三个表的行数）。

**输入从哪来 —— 两条路径别混：**

| 目的 | 输入 | 说明 |
| --- | --- | --- |
| **本地功能验证**（工具能不能跑通） | `scripts/test-docker.sh:105-111` 的 `clone_database()` | 它克隆的是**当前本地 compose 库**，**不是生产快照** ⇒ **不能用来定窗口长度** |
| **生产量级计时**（定窗口要的数） | 生产的 `pg_dump -Fc` 恢复进副本库 | 见下面命令 |

```bash
# 生产量级副本：建库 → 建 marker 表 → 灌入生产快照
createdb -h <host> -U <u> antflow_rehearsal
psql -h <host> -U <u> -d antflow_rehearsal -c \
  "CREATE TABLE rehearsal_marker(note text, created_at timestamptz DEFAULT now()); \
   INSERT INTO rehearsal_marker(note) VALUES ('由生产快照 <dump 文件名> 恢复，<日期>');"
pg_restore -h <host> -U <u> -d antflow_rehearsal --no-owner --no-privileges <prod.dump>

# 跑预演（--old-image-check 顺带做旧镜像的 Flyway 那道门）
bash scripts/rehearse-v54.sh --jdbc jdbc:postgresql://<host>/antflow_rehearsal \
  --user <u> --password <p> --confirm --old-image-check
```

**工具的三道闸**（靠边界，不靠命名约定）：库名不能是 `antflow`、必须带 `rehearsal_marker` 表
（上面那句 `CREATE` 就是它的来源，比库名约定硬）、迁移历史最后一行必须正好是 `53`
（按 `installed_rank` 取最后一行判，**不是** `MAX(version)`——那一列是 varchar，`MAX` 会得出
`'9' > '53'`）。它还会复跑 V54 的重复 `form_data_id` 查询并**列出 offender** 后中止。

**把工具打印的行数与源库对一遍**，对不上就别拿这组耗时定窗口。

---

## 4. 上线后验证（**必须做**）

**到这里 `company_id` / `company_inferred` 才存在**，这三条现在才能跑：

```sql
-- 4a) 唯一能抓到"新写入漏了归属"的检查。**必须为 0**。
--     started_at 在 V1__init.sql:99 是 TIMESTAMPTZ NOT NULL，所以问得出"这行是不是上线之后写的"。
SELECT count(*) AS leaked_new_rows FROM t_process_instance
WHERE company_id IS NULL AND started_at > :上线的时刻;

-- 4b) 回填结果是不是符合 §1 的 1) 与 2) 的预期。NULL 总数应 ≈ §1 的 will_stay_null。
SELECT company_id, count(*) FROM t_process_instance
GROUP BY company_id ORDER BY company_id NULLS FIRST;

-- 4c) 把非 NULL 部分拆开：历史**推断**归属 vs 历史**待确认**。
SELECT company_inferred, count(*) FROM t_process_instance GROUP BY company_inferred;
```

**4a 为什么是唯一证据**：`company_id` **没有** DEFAULT、**没有** CHECK、**没有** trigger；
`company_inferred` 只有 `DEFAULT false`（`V54:21,23`）。旧 schema 下这两列根本不存在，
所以"新写入漏了"和历史"待确认"两种行在库里都是 `(NULL, false)`，**库层分不出来**。
挡住误写的只有应用层 fail-closed（`ProcessEngine.java:89-92` 的 `BAD_COMPANY`），数据库本身不管。
4a 用 `started_at` 把"上线前就存在的 NULL"排除掉，才第一次能区分这两者。

**对照用的已知漏写面**（不要求你查，是给你判断"0 是不是理所当然"用的）：全部 4 条 INSERT
都经 `CompanyAttribution` 且 fail-closed —— `ProcessEngine.java:119/144`、
`FormDataService.java:125`、`MobileDraftService.java:57`；实例只有引擎一个创建口
（`DECISIONS.md:529`：无调度/作业/导入建实例）。

---

## 5. 恢复预案

### 5.1 已实测前提一：V54 失败是**原子的**

`%LOCALAPPDATA%\Temp\p4a\V54Rehearsal.java`，两个一次性探针库，走**真实 Flyway 路径**
（`target=53` 建库 → 造 fixture → 放开跑 V54）：

| | 触发 pre-flight 的库 | 干净库 |
| --- | --- | --- |
| V54 | **失败**（`P4a: 存在被多个实例引用的 t_form_data…`，`P0001`） | 通过 |
| 4 个新列 | **0 / 4** | 4 / 4 |
| 2 个索引 | **0 / 2** | 2 / 2 |
| `flyway_schema_history` 里的 V54 | **0 行**（连"失败行"都没有） | 1 行 `success=true` |
| 业务数据 | 完好 | 完好 |

Flyway 自己的日志：`Migration of schema "public" to version "54" failed! Changes successfully rolled back.`

**准确表述**：事务回滚保证的是**逻辑状态与迁移前一致**（结构、数据、迁移历史三样），
不是"物理字节相同"。别拿后者当时的说法去推断更细的东西。

**为什么必须重证一遍**：`PostgresTransactionalIntegrityIntegrationTest:490-495` 早就断言过
guard 会抛，但它走 `jdbcTemplate.execute(sql)`——那是**驱动层**的多语句隐式事务，
**不是**上线时跑 V54 的 Flyway 路径。

### 5.2 已实测前提二：**V54 成功之后**，旧镜像能起来

这是先前漏掉的一格。`%LOCALAPPDATA%\Temp\p4a\RollbackRehearsal.java`：
先把库跑到 V54（`history: 53=true 54=true`，4/4 列），再把 Flyway 的 locations 换成一个
**只有 V1..V53** 的目录（模拟旧镜像的 classpath）：

```
[B] old image validate() -> OK (applied=52, pending=0)
[B] old image migrate()  -> OK, migrationsExecuted=0
```

⇒ **历史表里那条解析不出的 V54，Flyway 10 按"future migration"容忍，不硬失败。**

**先前那条理由是错的**：我写过"旧代码不读这两列所以回退安全"——那压根没检查 Flyway，
而 Flyway 才是启动期的硬门。**已按实测结论替换。**

**还有一格**：`spring.jpa.hibernate.ddl-auto: validate`（基础 `application.yml:18-21`，
对**所有 profile** 生效）。旧实体没有 `company_id`/`company_inferred` 映射，而 `validate` 只查
"映射到的表/列在不在"、不查多余列 → **预期通过**。分两半，(a) 已实测通过，(b) 仍待做：

**(a) 机制代理（不需要旧镜像）—— ✅ 2026-10-10 已跑过，通过**：

在已到 V54 的探针库 `antflow_p4a_probe` 上 `ALTER TABLE t_process_instance ADD COLUMN
rehearsal_extra text`（**没有任何实体映射它**），然后跑 `mvn -B test -Dtest=ApiContractSnapshotTest`
—— 它 boot 的就是**真实应用上下文**，`ddl-auto` 取自 `application.yml`（`validate`）：

```
Tests run: 1, Failures: 0, Errors: 0   → BUILD SUCCESS
```

**上下文干净启动、日志里没有 `SchemaManagementException`** ⇒ "实体没映射的多余列不会让 `validate`
失败"这条机制成立（事后已 `DROP COLUMN` 还原）。这正是旧镜像面对 V54 的处境。

**复跑方式**（换成你自己的库）：

```bash
# 1) 在已到 V54 的副本上加一列没人映射的列
psql -h <host> -U <u> -d antflow_rehearsal \
  -c "ALTER TABLE t_process_instance ADD COLUMN rehearsal_extra text;"

# 2) 用当前 jar 以 validate 起一次（Flyway 对已在 V54 的库是空操作）
java -jar backend/target/antflow-backend-*.jar \
  --spring.datasource.url='jdbc:postgresql://<host>/antflow_rehearsal' \
  --spring.datasource.username=<u> --spring.datasource.password=<p> \
  --spring.jpa.hibernate.ddl-auto=validate \
  --antflow.mobile.files.storage=test --server.port=0

# 期望：上下文正常启动；日志里**没有** org.hibernate.tool.schema.spi.SchemaManagementException
# 收尾
psql -h <host> -U <u> -d antflow_rehearsal \
  -c "ALTER TABLE t_process_instance DROP COLUMN rehearsal_extra;"
```

**(b) 权威检查（窗口前，用 §2.4 存下的真镜像）** —— 把上面的 `java -jar` 换成
`docker run` 那个 **`antflow-local-backend:pre-v54`** tag，配一个 V54 的库副本起一次。
**(a) 是代理，(b) 才是权威**；只有 (b) 跑过，才能说"回退旧镜像"这一格闭合。

### 5.3 分场景

| 场景 | 数据库状态 | 处置 |
| --- | --- | --- |
| **V54 在启动时中止** | **仍是 V53**（§5.1：无半迁移状态、历史表无 V54 行） | 先人工处理那份重复挂载，或回退旧镜像（配 V53 本来就能跑）。**不需要 `flyway repair`**。**不要**先动库。 |
| **V54 成功、应用没起起来 / 健康检查不过** | **是 V54**（列已加、历史表有 V54 行） | 与上面的库状态**不同**，别混。① 若原因是应用侧（配置/端口/依赖），**优先修好重起新镜像**；② 要回退旧镜像的话，**前提是 §2.4 存下了 `pre-v54` tag**（没存就没有可回退的镜像），且 §5.2(b) 那一格要跑过。③ **注意代价**：回退后旧代码写的实例 `company_id` 是 NULL ⇒ **归因暂停**，§4 的 4a 会开始非 0，这不是 bug 而是本次回退的直接后果，要写进发布记录。 |
| 迁移成功、随后发现归属**写错** | V54 | 这时才用 §2 的备份。**回退镜像撤销不了已被回填的列值**。 |
| 迁移成功但 4a 查出**漏写**非 0 | V54 | 不是迁移问题，是应用 bug。按 `ProcessEngine.java:89-92` 那条 fail-closed 链往回查调用方。 |

**一句话**：`V54 失败` 时库没变、回退便宜；`V54 成功之后` 库已经变了，
回退可行但会让**归因暂停**，所以那时优先"修好再起新镜像"而不是回退。

### 5.4 恢复手段的排序

**首要动作是"修好再起"或"回退旧镜像"**，**不是**从备份恢复——因为 §5.1/§5.2 下
库要么没变、要么回退镜像还能跑，都没有"东西要恢复"。整库备份是**最后兜底**（§2.3）。

---

## 6. 历史 `company_id IS NULL` 的处理（已定）

### 6.1 政策

**接受保持 NULL，上线后按需确认。** 不自动归属、不按发起人今天的部门猜。

口径依据：`D-20261009-company-attribution-policy` 与 `DECISIONS.md:505-516`。
三类历史记录（`V54:57-59`）：

1. 有 `started_dept_id` 且部门仍在 → 已回填，标 `company_inferred = true`（**是推断值**，
   因为 `started_dept_id` 本身就是 `V20:88-94` 当年按"迁移当时"的用户部门推断的）。
2. `started_dept_id IS NULL`（含 seed `admin` 的无部门发起、系统发起）→ **保持 NULL**。
3. 没有实例的 `t_form_data`（无流程提交、**草稿**）→ **保持 NULL**（没有部门快照可依）。

§1 的盘点结果只用来评估规模，**不进文档**。

### 6.2 历史草稿：`draft handler` 逐条回填（**这不是"可选后续"，是上线后立刻要做的事**）

**为什么紧迫**：V54 的 step 2 只按实例回填（`V54:49-55`），而草稿没有实例 ⇒
**全部** V54 之前的草稿都是 `company_id IS NULL`。而 `forDraft` 的
`storedCompanyId == null` 检查在**最前面**（`CompanyAttribution.java:71-77`），
在它之后才看 `explicitCompanyId` ⇒

- 现有公司选择器**结构上救不了**旧草稿（NULL 时根本不看请求里的公司）；
- **提交被阻**，而且 `MobileDraftService.update:65` 也过 `forDraft` ⇒ **保存也被阻**；
- 两端草稿都存 `t_form_data status='DRAFT'`（**没有** `t_mobile_draft` 表）；
- 桌面端不经 UI 触达（`frontend/src/pages/runtime/form/Fill.tsx:130` 写死 `draftId: null`），
  **移动端是硬阻断**（HTTP 422）。

**时间线**（`company_id` 是 V54 才有的列 ⇒ 枚举/回填只能在**窗口后**）：

| 时点 | 动作 |
| --- | --- |
| **窗口前** | 只用旧 schema 计数（**不许**查 `company_id`）：`status='DRAFT'` + `NOT EXISTS instance` 就是全部历史草稿数 |
| **窗口后** | 枚举明细 → `draft handler` 逐条确认 → 回填 → 验证 + 功能抽查 |

**枚举**：

```sql
SELECT fd.id, fd.form_def_id, fd.created_by, fd.created_at, fd.updated_at
FROM t_form_data fd
WHERE fd.status = 'DRAFT' AND fd.company_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM t_process_instance pi WHERE pi.form_data_id = fd.id)
ORDER BY fd.created_at;
```

**回填**——事务 + 行锁 + **同一组三层守卫**，且**影响行数必须为 1**：

```sql
BEGIN;

-- 1) 先锁住并确认"这一条确实是历史草稿"。三层守卫缺一不可：是草稿 / 归属为空 / 没有实例。
--    返回 0 行 ⇒ 它已经不是历史草稿（已回填 / 已转正 / 有实例）→ ROLLBACK，别硬改。
SELECT id, form_def_id, created_by
FROM t_form_data
WHERE id = :draft_id
  AND status = 'DRAFT'
  AND company_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM t_process_instance pi
                  WHERE pi.form_data_id = t_form_data.id)
FOR UPDATE;

-- 2) 带**同一组**守卫的 UPDATE（防并发下 id 已被改走 / 已被别人回填）
UPDATE t_form_data
SET company_id = :company, company_inferred = false   -- 人确认的，所以 false（不是推断值）
WHERE id = :draft_id
  AND status = 'DRAFT'
  AND company_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM t_process_instance pi
                  WHERE pi.form_data_id = t_form_data.id);

-- 3) 必须影响 1 行。0 行 → ROLLBACK 并查明原因。
COMMIT;
```

> **没有这组守卫会静默改错**：只按 `id` 更新就可能改到已转正或已确认的记录。

**验证**——分开报"历史遗留"与"上线后新增"，**两个都要看**：

```sql
-- (i) 历史遗留的 NULL 草稿：回填后应归零
SELECT count(*) AS legacy_drafts_null
FROM t_form_data fd
WHERE fd.status = 'DRAFT' AND fd.company_id IS NULL
  AND fd.created_at < :deploy_ts
  AND NOT EXISTS (SELECT 1 FROM t_process_instance pi WHERE pi.form_data_id = fd.id);

-- (ii) 上线之后才出现的 NULL 草稿：必须为 0（非 0 = 应用漏写，是 bug 不是历史遗留）
SELECT count(*) AS new_drafts_null
FROM t_form_data fd
WHERE fd.status = 'DRAFT' AND fd.company_id IS NULL
  AND fd.created_at >= :deploy_ts
  AND NOT EXISTS (SELECT 1 FROM t_process_instance pi WHERE pi.form_data_id = fd.id);
```

**"归零"不是成功口径**：回填只解掉 `COMPANY_PENDING` 一个门。草稿还可能因为**别的**规则
不能保存/提交——表单不再 `PUBLISHED`、`form_def_version` 与当前版本不一致
（`MobileWorkflowService.start` 抛 `DRAFT_VERSION_MISMATCH`）、`readOnly`、以及
`requireFormUse` 的权限变化。所以还要报一遍这些：

```sql
SELECT fd.id, fd.form_def_id, fd.form_def_version,
       f.version AS current_version, f.status AS form_status
FROM t_form_data fd JOIN t_form_definition f ON f.id = fd.form_def_id
WHERE fd.status = 'DRAFT' AND fd.company_id IS NOT NULL
  AND (f.status <> 'PUBLISHED' OR fd.form_def_version <> f.version);
```

并**至少抽查一条回填过的草稿走真实路径**：移动端 `PUT /api/mobile/drafts/{id}` 能保存；
若表单仍发布且版本一致，再验一次提交。**不要**把"NULL 归零"等同于"用户已经能用"。

**两条附注**：

1. 这条路径顺带让现有提示变真。`forDraft` 抛的是
   `…暂时无法提交；请联系管理员确认归属后再提交`——在没有确认机制时它是空话，
   有了 `draft handler` + 上面那条 SQL 才成立。所以**不必改文案**。
2. **已知粗糙点，记录不修**：`MobileDraftService.update:65` 复用同一个 `forDraft`，
   于是**保存**草稿时抛的是那句"暂时无法**提交**"。文案对保存路径不准确。
   零成本修法是给 `update` 单独文案，但那属于改产品代码，本 runbook 不做。
