# 任务交接

- **State:** active
- **更新时间：** `2026-09-29T19:10:00+08:00`
- **分支：** `feat/contacts-report-export`（从 `master` = `4328845` 拉出，未推）。前两轮都已合并进 master：**PR #2** = 实测反馈 17 条、**PR #3** = 选项数据源按 demo 重排。

---

## 最新一轮：通讯录搜人 / 台账三列 / 报表三个空壳落地

你报了三件事，计划里先核对了现状（都在本机 docker 栈真数据上验过），实现分 5 个提交：

| 提交 | 内容 |
| --- | --- |
| `2ef0337` | **通讯录**：搜索框一个框双用（同一个词既过滤左树、又跨部门搜人，走已有的 `/api/users/page`）；点结果「定位」跳到 TA 所在部门；面包屑改从已授权树推导（原来那条 `/departments/{id}/path` 只认部门权限，跨部门跳转会 403）；**后端修** `listAuthorizedPage` 对 ALL 范围漏掉 `dept_id IS NULL` 的成员 |
| `697de89` | **台账三列**：提交人显示姓名（原来显示登录账号）+ 工号 + 部门；提交人筛选从数字 id 改成按姓名/工号关键字（EXISTS 子查询，无匹配=零条，`%`/`_` 转义）；修空集合批查与 `Map.of().get(null)` 的 NPE |
| `243e9ac` | **报表中心**：抽出 `authz/InstanceScopeSql`（监控与报表共用一份范围口径）+ `GET /api/reports/approval-summary`（合计/按表单/按部门/按天，一次给全，报表与看板共用）；页面按设计稿做横条仪表盘 + 内联通过率条 |
| `9204271` | **数据看板**：趋势折线当主角 + 部门通过率/表单占比两个小面板，同一接口同一筛选 |
| `cf394bc` | **数据导出**：CSV（带 UTF-8 BOM，Excel 不乱码）+ 10000 行上限 + HIGH 审计；预览行数与导出范围同源；结构化的字段值（检查项）改成可读的键值串 |

**口径都写在代码注释里**：报表按发起时间归期、通过率 = 通过 ÷（通过+驳回）、平均耗时只算已终态；状态桶含 `other` 兜底；**不按 node 分组**（监控页的节点驳回率按 node_id 跨流程合并，会混）。

**自评截图**：`.claude/shots/` 的 `contacts-people-search.png`、`ledger-three-columns.png`、`report-center.png`、`report-dashboard.png`、`report-export.png`。自评抓到并修掉的两个真问题：
- **看板的图表把整页撑出横向滚动条**：G2 只在挂载时量一次容器（那时侧栏还没渲染，量宽了 200px），而它自带的 autoFit 之后不再重算。改成 `autoFit: false` + `ResizeObserver` 量到宽度再挂载（`key` 跟宽度），容器加 `min-width: 0; overflow: hidden` 打破"canvas 撑宽容器→量到更大宽度"的循环。量到的第一次同样是 bug：容器在"加载中"还不存在，只跑一次的 effect 拿到 null ref 就再也不量了 → 加 `mounted` 依赖。
- **通讯录左树搜不到部门时一片空白**：补了一行「没有匹配的部门——右侧是人员搜索结果」。

**越权面实测**（建了个 SELF 范围的探针账号，走 HTTP，用完已删）：搜别人的名字 → 0 条、搜自己工号 → 1 条；报表 → 全 0（不是全库）；导出 → 只有表头；`form:data:export` 而没有 `form:data:read` → 403（不是空文件）。

### OCR 复核（`ocr scan`，8 文件 28 条）

**采纳 8 条**：`submitterKeyword` 的空集合会退化成"返回全部"（改成 EXISTS 子查询）、ALL 范围漏无部门成员、面包屑 403、空集合批查、报表状态桶要含 `other`、报表别抄监控页的 node 分组、`PAGE_SIZE` 共享常量、字典请求静默降级。

**驳回 3 条**：`== null` 风格条、静态内联样式、`any`→`unknown`（都与本仓库约定不符，Biome/CI 现状已确认）。

### 本轮留档不改（都写进下面的"已知遗留"）

本人提交列表无分页（`selectMySubmissions` 无 LIMIT、每条带完整 JSONB）；表单提交的 `status` 没有白名单校验；**台账用当前 schema/字典解释旧记录**（忽略 `formDefVersion`，字段改名/删选项后旧提交标签会错）——这条最值得下一轮单独做；抽屉元数据与记录不绑定；通讯录既有的一批（导出只含当前页、部门移动后不刷新成员、删空末页不回位、负责人候选只取 100 人且不可键盘操作、清空上级不持久化、凭据入口只按 admin 显示）。

---

## 前两轮（都已合并）

- **PR #3 = 选项数据源按 demo 的信息结构重排**：列表改表格 + 详情抽屉 + 版本 Timeline 卡片 + 版本对比 + 生命周期弹窗；V50 版本「变更说明」；修 `importDraft` 漏递增 `source.version`（乐观锁洞）与 `unpublish` 不清 `disabled_at`（版本状态机洞）。同轮 OCR 30 条 → 采纳 8 / 驳回 3 / 留档 1（行表 GIN 索引确实没被任何查询用到，删它要单独一条迁移 + EXPLAIN）。
- **PR #2 = 实测反馈 17 条**：监控页回归（Java 文本块吞行尾空格 → `AND1 = 1`）、登录 401 不整页刷新、节点名显示中文、指定人员 400、发起人按 ROOT 判隐藏、条件值读外部数据源、**CC 抄送永不投递**（`Map.of` 撞 null）。验证基线见下。

## 已知遗留（三轮累计）

- **`npx antd lint ./src` 从来不是干净的**：现在 61 deprecated + 42 usage（`Alert` 的 `message`、`Space direction`、模板页里的静态 `message.xxx` 等）。`frontend/CLAUDE.md` 把它列为提交前必过项，实际不是门槛——新代码与既有写法保持一致，别单独给新文件换风格（否则同一个页面两种写法）。要做就整仓一次性刷。
- **台账的历史版本解释**（上一轮 OCR 也提了）：字段标签/选项/检查项字典都取**当前**定义，忽略记录的 `formDefVersion`。表单改名/删选项之后旧提交会显示错的标签。修法：按记录的表单版本（或修订版）取 schema/字典，取不到回落原始值。
- 本人提交列表无分页；`status` 无白名单校验（能写进 `APPROVED` 等非法值，报表里落进 `other` 桶）。
- 通讯录的一批既有问题（见上）。桌面用户/部门选择「默认填充」与「候选范围」是两件独立配置，范围把本人排除时下拉显示裸 id。
- **outbox 投递租约可能重复投递**；**`HIDDEN` 不是保密边界**；前端测试环境关掉了"未处理错误"安全网（`dangerouslyIgnoreUnhandledErrors`）；移动端 lint 既有错误本轮未动；台账 `table_list` 明细没按列展开；存量角色权限三条（V40）只记录不改。

## 验证基线

后端 `mvn -B test` **496**、前端 `npm test` **274**、移动 `npm test` **348**；前端 `biome:lint` 4 warnings（既有）+ `tsc` 干净，移动 `biome lint` 干净。**CI 只跑 `biome lint`（不含格式化与导入排序）——别用 `biome check --write` 全量刷**。新加的守护用例都验过"去掉修复即失败"（ALL 范围漏人、`submitterKeyword` 空集合、报表三种范围、导出范围收窄）。

> 更早：`63582d5` = S1–S4 整改 + 收尾（决策见 `docs/DECISIONS.md` 的 `D-20260921` ~ `D-20260924-*`）。本轮新增 `D-20260929-export-follows-read-scope`。
