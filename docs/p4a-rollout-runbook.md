# P4a 上线 runbook（公司归属快照 / V54）

> **owner:** 待定（谁负责历史归属 `company_id IS NULL` 的后续确认 + 上线验收）
> **window:** 待定（维护窗口时间）
>
> 这两项见 `HANDOFF.md` 顶部"仍差"。**未填不影响本 runbook 可用**，但上线前必须落名。

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

## 2. 备份（**最后兜底**，不是主要恢复手段）

它只在"迁移**成功**、随后才发现业务问题"这种场景下有用；"迁移失败"不需要它（见 §5）。
但正因为那是唯一兜底，**命令要能照着敲**，不能只指向一个目录。

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

---

## 3. 上线步骤

1. **镜像在窗口前就构建好**：`docker compose --env-file .env.docker.local build backend web`。
   把编译时间移出窗口。
2. **停旧容器**。
3. **起新版本**：`docker compose --env-file .env.docker.local up -d backend web`
   （V54 随启动生效）。
4. **健康检查**：`http://127.0.0.1:7070/actuator/health`（`deploy.sh:192-193` 用同一个探针）。
   起不来的处置见 §5。

### 窗口时长要按**整个 Flyway migrate** 估，不只是两个 UPDATE

V54 在窗口里要干的全部事情（`V54:20-23,42-55,61-64`）：

| 步骤 | 开销 / 锁 |
| --- | --- |
| 4 × `ALTER TABLE ADD COLUMN`（2 表各 2 列） | 各取 **ACCESS EXCLUSIVE**（阻塞一切读写）。`company_inferred` 带 `DEFAULT false`，PG 11+ 不重写表；`company_id` 带 `REFERENCES`，新列全 NULL 所以外键校验是空的，但 `ALTER` 本身的锁要等**长事务**让路 |
| `DO $$ … RAISE … $$`（pre-flight） | 一次 `GROUP BY` 全表扫描 |
| `UPDATE t_process_instance … FROM t_department` | 全表回填 + 行锁 |
| `UPDATE t_form_data … FROM t_process_instance` | 同上 |
| 2 × `CREATE INDEX` | **不是 `CONCURRENTLY`** → 取 SHARE 锁，**建索引期间该表无法写入**。大表上这一步常比回填更贵 |

⇒ **在副库上掐表要掐完整的 `Flyway migrate`**（不是单跑两条 UPDATE），
并记下各段耗时与有没有**锁等待**。**这个数只能在生产量级的副库上量，本文件不给估算值。**

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

**仍未实测的一格（要在副库补）**：`spring.jpa.hibernate.ddl-auto: validate`（`application.yml:21`）。
旧实体没有 `company_id`/`company_inferred` 映射，而 `validate` 只查"映射到的表/列在不在"、
不查多余列，所以**预期通过**——但这是**推理，不是实测**。
**窗口前补一步（两分钟）**：用**上一个镜像 tag** 配一个 V54 的库副本起一次，看能不能过。

### 5.3 分场景

| 场景 | 数据库状态 | 处置 |
| --- | --- | --- |
| **V54 在启动时中止** | **仍是 V53**（§5.1：无半迁移状态、历史表无 V54 行） | 先人工处理那份重复挂载，或回退旧镜像（配 V53 本来就能跑）。**不需要 `flyway repair`**。**不要**先动库。 |
| **V54 成功、应用没起起来 / 健康检查不过** | **是 V54**（列已加、历史表有 V54 行） | 与上面的库状态**不同**，别混。① 若原因是应用侧（配置/端口/依赖），**优先修好重起新镜像**；② 要回退旧镜像的话，按 §5.2 已实测可行，但先做完那一格未实测的验证。③ **注意代价**：回退后旧代码写的实例 `company_id` 是 NULL ⇒ **归因暂停**，§4 的 4a 会开始非 0，这不是 bug 而是本次回退的直接后果，要写进发布记录。 |
| 迁移成功、随后发现归属**写错** | V54 | 这时才用 §2 的备份。**回退镜像撤销不了已被回填的列值**。 |
| 迁移成功但 4a 查出**漏写**非 0 | V54 | 不是迁移问题，是应用 bug。按 `ProcessEngine.java:89-92` 那条 fail-closed 链往回查调用方。 |

**一句话**：`V54 失败` 时库没变、回退便宜；`V54 成功之后` 库已经变了，
回退可行但会让**归因暂停**，所以那时优先"修好再起新镜像"而不是回退。

### 5.4 恢复手段的排序

**首要动作是"修好再起"或"回退旧镜像"**，**不是**从备份恢复——因为 §5.1/§5.2 下
库要么没变、要么回退镜像还能跑，都没有"东西要恢复"。整库备份是**最后兜底**（§2.3）。

---

## 6. 历史 `company_id IS NULL` 的处理（已定）

**接受保持 NULL，上线后按需确认。** 不自动归属、不按发起人今天的部门猜。

口径依据：`D-20261009-company-attribution-policy` 与 `DECISIONS.md:505-516`。
三类历史记录（`V54:57-59`）：

1. 有 `started_dept_id` 且部门仍在 → 已回填，标 `company_inferred = true`（**是推断值**，
   因为 `started_dept_id` 本身就是 `V20:88-94` 当年按"迁移当时"的用户部门推断的）。
2. `started_dept_id IS NULL`（含 seed `admin` 的无部门发起、系统发起）→ **保持 NULL**。
3. 没有实例的 `t_form_data`（无流程提交、草稿）→ **保持 NULL**（没有部门快照可依）。

§1 的盘点结果只用来评估规模，**不进文档**。
