# P4a 上线 runbook（公司归属快照 / V54）

> **owner:** 待定（谁负责历史归属 `company_id IS NULL` 的后续确认 + 上线验收）
> **window:** 待定（维护窗口时间）
>
> 这两项见 `HANDOFF.md` 顶部的"仍差"。**未填不影响本 runbook 可用**，但上线前必须落名。

本 runbook 只覆盖 **V54 + 新应用一起上线**这一件事。它**不改读权限**——公司隔离是 P4 后续的事。

---

## 0. 先理解一件事：这不是滚动发布，是停机重启

`HANDOFF.md` 早先写的"迁移先上、应用后上"**在本仓做不到**，别照它试。实测依据：

- **单实例**：`compose.yaml:41-86` 只有一个 `backend` 服务，全仓 grep 不到
  `replicas` / `--scale` / `deploy:`；`infra/docker/nginx.conf:24-52` 是
  `proxy_pass http://backend:8080`，**没有 `upstream` 块**。
- **Flyway 在应用容器启动时跑**（`application.yml:26-29`，`enabled: true`）；
  `deploy.sh` / `Dockerfile*` / CI 里**都没有**独立迁移步骤。

⇒ `docker compose up -d --build backend` 对单副本就是**先停后起**，新旧版本**不可能同时在线**，
所以"旧实例和新实例长期混跑、旧实例写出没有 `company_id` 的新记录"这个风险在本仓**不存在**。
迁移与应用必然同时生效——这是本 runbook 全部步骤的前提。

---

## 1. 上线前：只读检查（**窗口外做**，结果只用于判断风险，不进文档）

三条盘点（`t_form_data` 同样做一条 `company_id IS NULL` 统计）：

```sql
SELECT count(*) AS no_department       FROM t_process_instance WHERE started_dept_id IS NULL;
SELECT count(*) AS no_company_snapshot FROM t_process_instance WHERE company_id IS NULL;
SELECT company_id, count(*)            FROM t_process_instance
  GROUP BY company_id ORDER BY company_id NULLS FIRST;
```

**加这条，它是上线前唯一必须看的一条**：

```sql
-- pre-flight 的只读等价物（对应 V54:30-32）。
-- 空集 ⇒ V54 不会因它中止。非空 ⇒ 停，先人工处理那几份 form_data，别往下走。
SELECT form_data_id, count(*) FROM t_process_instance
  GROUP BY form_data_id HAVING count(*) > 1;
```

写法安全性：`form_data_id` 在 `V1__init.sql:94` 是 `NOT NULL REFERENCES`，
所以 `GROUP BY` 不会把 NULL 归成一组误报。

可选（只影响你怎么向业务解释，不影响决策）：

```sql
SELECT company_inferred, count(*) FROM t_process_instance GROUP BY company_inferred;
```

### 为什么这条检查值得单独跑（它的价值已被重新定位）

V54 失败**损坏不了任何东西**（见 §5），所以这条检查**不是**用来"防损坏"的。
它的价值是**别在停机窗口里才发现**——把发现时机从窗口内前移到窗口前。
发现非空时人工处理一份 `form_data` 要多久，就是省下来的窗口时间。

---

## 2. 备份（**最后兜底**，不是主要恢复手段）

整库快照。仓库已有 `backend/backup/` 的备份/恢复脚本可复用。

它只在"迁移**成功**了、随后才发现业务问题"这种场景下有用。
"迁移失败"不需要它——见 §5。

---

## 3. 上线步骤

1. **停旧容器**。
2. **起新版本**：`docker compose --env-file .env.docker.local up -d --build backend web`
   （V54 随启动生效）。
3. **健康检查**：`http://127.0.0.1:7070/actuator/health`。
   `deploy.sh:192-193` 用的是同一个探针。起不来的处置见 §5。

**窗口长度 ≈ 回填耗时**。V54 在窗口里要干两件花钱的事（`V54:42-55`）：

- `UPDATE t_process_instance ... FROM t_department ...`（按 `started_dept_id` 映射公司）
- `UPDATE t_form_data ... FROM t_process_instance ...`（沿用所属实例）
- 外加两个索引 `ix_process_instance_company` / `ix_form_data_company`。

**这个数只能在生产量级的副库上量，本 runbook 不给估算值。** 量法：把生产快照灌进一个副库，
在它上面跑一遍 V54（`Flyway` target 放开即可），掐表。**这就是"预演"的全部内容**，
它同时验证了 §1 那条检查确实为空。

---

## 4. 上线后验证（**必须做**）

```sql
-- 唯一能抓到"新写入漏了归属"的检查。**必须为 0**。
-- started_at 在 V1__init.sql:99 是 TIMESTAMPTZ NOT NULL，所以问得出"这行是不是上线之后写的"。
SELECT count(*) AS leaked_new_rows FROM t_process_instance
WHERE company_id IS NULL AND started_at > :上线的时刻;

-- 顺带看历史拆分是否符合预期（不影响是否回滚）
SELECT company_inferred, count(*) FROM t_process_instance GROUP BY company_inferred;
```

**为什么这条是唯一证据**：V54 **没有** trigger、**没有** CHECK、**没有** DEFAULT、索引也
**不是 partial**（只有 `company_inferred BOOLEAN NOT NULL DEFAULT false`，`V54:21,23`）。
所以一条 `company_id = NULL, company_inferred = false` 的**新**行与一条"历史待确认"行
在库里**逐字节无法区分**。挡住误写的只有应用层 fail-closed（`ProcessEngine.java:89-92` 的
`BAD_COMPANY`），数据库本身不管。

上线前跑这条**没有意义**（那时还没有新行）；上线后**必须为 0**。

**对照用的已知漏写面**（不要求你去查，是给你判断"0 是不是理所当然"用的）：全部 4 条 INSERT
都经 `CompanyAttribution` 且 fail-closed —— `ProcessEngine.java:119/144`、
`FormDataService.java:125`、`MobileDraftService.java:57`；实例只有引擎一个创建口
（`DECISIONS.md:529`：无调度/作业/导入建实例）。**没有主代码路径能在新行上写出 NULL。**

---

## 5. 恢复预案

### 5.1 已实测前提：V54 是**原子**的

`%LOCALAPPDATA%\Temp\p4a\V54Rehearsal.java` 在两个一次性探针库上跑了真实 Flyway 路径
（`target=53` 建库 → 造 fixture → 放开跑 V54）：

| | 触发 pre-flight 的库 | 干净库 |
| --- | --- | --- |
| V54 结果 | **失败**（`P4a: 存在被多个实例引用的 t_form_data…`，`P0001`） | 通过 |
| 4 个新列 | **0 / 4** | 4 / 4 |
| 2 个索引 | **0 / 2** | 2 / 2 |
| `flyway_schema_history` 里的 V54 | **0 行**（连"失败行"都没有） | 1 行 `success=true` |
| 业务数据 | 完好 | 完好 |

Flyway 自己的日志：`Migration of schema "public" to version "54" failed! Changes successfully rolled back.`

**为什么不能只用已有的那个用例**：`PostgresTransactionalIntegrityIntegrationTest:490-495`
确实断言过 guard 抛 `被多个实例引用`，但它走 `jdbcTemplate.execute(sql)`，
是**驱动层**的多语句隐式事务，**不是**上线时真正跑 V54 的 Flyway 路径。两条路径已分别证过。

### 5.2 因此：先把"修好再起 / 回退镜像"做完，备份是兜底

1. **回退镜像是安全且即时的**：失败时库与迁移前**逐字节相同**，而 `company_id` 全为 NULL、
   **旧代码根本不读这两列** → 旧镜像配 V53 schema 照常跑。
2. **不需要 `flyway repair`**：失败路径下 `flyway_schema_history` 里**一行都没有**，
   下次启动会干净地重试 V54。这一条要紧，因为仓库的规矩是 forward-only、
   **禁止用 repair 掩盖状态**（`D-20260917-flyway-forward-only`）。
3. **主要恢复手段不是从备份恢复** —— 因为没有东西要恢复。
4. **剩下唯一的真实风险是停机时长，不是数据损坏。**

### 5.3 分场景

| 场景 | 处置 |
| --- | --- |
| V54 在启动时中止（§1 那条检查会提前抓到） | 库未变。人工处理那份 `form_data` 的重复挂载，或回退旧镜像取消本次发布。**不要**先动库。 |
| 健康检查不过但不是 V54（配置/端口/依赖等） | 与本次迁移无关；按既有方式排查。库仍是 V53 原样。 |
| 迁移成功、随后发现归属写错 | 这时才用 §2 的备份。因为迁移已提交，回退镜像也**撤销不了已被回填的列值**。 |
| 迁移成功但发现漏写（§4 那条非 0） | 不是迁移问题，是应用 bug。按 `ProcessEngine.java:89-92` 那条 fail-closed 链往回查调用方。 |

---

## 6. 历史 `company_id IS NULL` 的处理（已定）

**接受保持 NULL，上线后按需确认。** 不自动归属、不按发起人今天的部门猜。

口径依据：`D-20260909`/`D-20261009-company-attribution-policy` 与 `DECISIONS.md:505-516`。
三类历史记录（`V54:57-59`）：

1. 有 `started_dept_id` 且部门仍在 → 已回填，标 `company_inferred = true`（**是推断值**，
   因为 `started_dept_id` 本身就是 `V20:88-94` 当年按"迁移当时"的用户部门推断的）。
2. `started_dept_id IS NULL`（含 seed `admin` 的无部门发起、系统发起）→ **保持 NULL**。
3. 没有实例的 `t_form_data`（无流程提交、草稿）→ **保持 NULL**（没有部门快照可依）。

§1 的盘点结果只用来评估这段的规模，**不进文档**。
