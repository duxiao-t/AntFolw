# 任务交接

- **State:** active
- **更新时间：** `2026-09-30T13:00:00+08:00`
- **分支：** `feat/contacts-report-export`（从 `master` = `4328845` 拉出，未推）。前两轮都已合并进 master：**PR #2** = 实测反馈 17 条、**PR #3** = 选项数据源按 demo 重排。

---

## 最新一轮：水印缺 ffmpeg 的 422 + 桌面端媒体组件从来没上传

你报「5S检查表里上传图片报 422」并让我顺手查其它多媒体组件，查出**两个独立问题**：

| 提交 | 内容 |
| --- | --- |
| `f6d9b12` | **缺 ffmpeg**：镜像装 `ffmpeg` + `fonts-wqy-microhei`；`MediaWatermarkProcessor` 加启动探测（缺 ffmpeg / 缺中文字体分别 WARN）；报错换成可操作文案（缺 ffmpeg / 处理失败首行 / 超时三种）；`-version` 探测与 `run()` 都改成"重定向输出 + waitFor"（原来先读管道再等，输出一多就卡死误判超时）；`watermark=true` 但没文案从**静默存原图**改成显式 422；图片按魔数校验（非图片字节不再进 ffmpeg）；`MobileFileDto` 补 `failureReason`（客户端轮询到 FAILED 才知道为什么）；CI 也装 ffmpeg，否则那两条真机用例一直被 `assumeTrue` 跳过 |
| `207e301` | **桌面端四个上传控件从来没发请求**：`beforeUpload={() => false}` + 值里写 antd `UploadFile`，`collectFileRefs` 只认 `id`+`contentType` → 选了文件、提交成功、**附件静默丢失**（检查项照片连 `onChange` 都没有）。改成共用 `MediaUploadControl`（`customRequest` 真上传，走移动端同一个 `/api/mobile/files`）→ 值写服务端 DTO → 只读态用新抽的 `MediaPreview`（鉴权 blob）显示缩略图/播放器/下载；检查项"全部设为"不再清空照片与描述；提交校验挡住"还在传/服务端还没处理完"的文件（含检查项照片与明细表行内媒体） |

**根因分层**：422 是"水印要在写进 MinIO **之前**跑 ffmpeg，而镜像里没有 ffmpeg"；桌面端不上传是"控件只是把本地文件放在内存里，提交时收集不到"。存储本身没变——生产仍是 `MinioFileStorage`，桌面端接的是同一个桶。

**OCR 复核（`ocr scan` 12 文件 91 条）改了四处做法**：值里**只放 READY 的 DTO**（服务端关联只接受 READY，否则整单被拒）；待上传检查要覆盖检查项照片（在 `items[].images` 里）与明细表行内媒体；`run()` 的等待方式本身有坑（管道填满 → 误判超时），探测不能照抄；`watermark=true` + 空文案的静默跳过要改成显式拒绝。

### 真机验证（镜像 --no-cache 重建后，桌面 + 移动 + 容器三方）

- **镜像里有 ffmpeg 了**：`docker exec ... command -v ffmpeg` → `/usr/bin/ffmpeg`，字体 `/usr/share/fonts/truetype/wqy/wqy-microhei.ttc`；启动日志 `媒体水印就绪：ffmpeg='ffmpeg'，字体='.../wqy-microhei.ttc'`。
- **你报的那个 422 没了**：移动端 `5S检查表` 的「现场照片」（`watermark=true`）上传 → 200、`READY`，字节数从 1758 变 1745（ffmpeg 重编码 = 真加了水印）。用一张纯色图上传后把水印裁出来看，是「现场留证 2026-09-30 05:13」——中文渲染正常（截图 `.claude/shots/watermark-plain-demo.png` / `watermark-zoom.png`）。
- **反例**：同一个镜像以 `FFMPEG_BIN=/nope/ffmpeg` 起一个探针实例 → 启动 WARN 写清原因，上传返回 **可读的 422**：「服务器缺少 ffmpeg，无法为图片/视频添加水印。请联系管理员安装 ffmpeg（或配置 antflow.media.ffmpeg-bin）后重试。」（不再是 `Cannot run program "ffmpeg"`）。
- **桌面端四类媒体都真上传了**：`未命名表单0902` 填单上传 图片/视频/附件/检查项照片 → 提交后 `t_form_data_file` 出现 4 条关联（image 98696、`images`、video、text/plain），详情页能看到缩略图与视频播放器（截图 `desktop-media-all-kinds-uploaded.png`、`desktop-detail-new-submission-media.png`）。
- **桌面图片字段带上了水印参数**：抓包确认 multipart 里有 `watermark=true` + `watermarkText=AntFlow`（字段配置），存储对象从 132KB 变 99KB（重编码）。
- **只读预览**：老单据（实例 1）的详情页正常渲染检查项照片缩略图、图片缩略图与两个视频播放器（鉴权 blob 通道）。

**自评截图**：`.claude/shots/` 的 `watermark-plain-demo.png`、`watermark-zoom.png`、`desktop-checklist-photo-uploaded.png`、`desktop-media-all-kinds-uploaded.png`、`desktop-detail-new-submission-media.png`、`mobile-5s-photo-uploaded.png`。

**一个要记下的代价**：后端镜像 1.01GB → **1.57GB**（ffmpeg 在 Debian 上会拖进 mesa/llvm 等一大串，比预估的 +150MB 多得多）。如果生产在意体积，可以换成静态 ffmpeg 二进制或用独立的媒体处理服务；本轮先按"装全"走。

### 本轮留档不改（OCR 标了 high 但属既有问题，你说了下一轮再开）

- **服务端并发/事务**：SHA 去重会覆盖旧对象却返回旧行；先写 MinIO 后插行，回滚留孤儿对象；`delete` 在事务提交前删对象；`countLinks` 与删除不原子；`processVideoWatermark` 无独占认领、与 `delete` 竞态可把已删对象写回来（**桌面端只加了"处理中不给删"的挡板**）；`stage()` 只信 `MultipartFile` 声明的大小。
- **性能**：视频走 `byte[]` 全量进出（100MB × 并发 2 可能 OOM，且 OOM 不在 `catch(Exception)` 里 → 行永远 PROCESSING）；启动恢复一次性把所有待处理视频入队、满队列直接标 FAILED。
- **前端既有**：`displayConditions` 的可见性依赖遍历顺序（引用后面的字段会被误隐藏，与后端 `resolveDefinition` 口径不同）；`table_list` 不逐行校验、不校验 minRows/maxRows；检查项没有 `validate`（逐项 required / descriptionRequiredByResult / photoMaxCount 都没查）；`nativeMedia.ts` 的录音/扫码资源清理；`VideoUploadField` 的 `maxDuration` 只在设计器预览里显示、运行时不校验。
- **移动端检查项照片**不传水印参数（检查项节点没有水印开关，等有开关时一行接上）。

---

## 上一轮：通讯录搜部门出人 / 台账按版本解释 / 导出支持 Excel

你报了三件事，都先在本机 docker 栈的真数据上核对了现状再动手：

| 提交 | 内容 |
| --- | --- |
| `0f9bd3b` | **通讯录搜部门名出人**：后端关键字补一条 ltree 谓词（命中部门名的**该部门及全部下级**的成员），桌面搜索与移动选择器共用；左树过滤改成"命中节点保留整棵子树" |
| `bbf3e85` | **台账按记录自己的版本解释**：新增 `form/runtime/FormValueDisplay`（把前端 `fieldValues.ts` 的规则搬成 Jackson 版并补缺口），`enrich` 按**每条记录**解析 schema + 批量回填 `displayText`/`detailText`；前端删掉本地那套规则与那次可选的定义请求 |
| `0db1139` | **导出 Excel/CSV**：`FormDataCsv` → `FormDataExport`（列按字段 id、值取显示文本、公式注入防护、SXSSF 全 STRING 单元格）；接口加 `format=csv\|xlsx`（默认 xlsx）；预览计数补上时间范围 |

**为什么"按记录自己的版本"不是前端能修的**：版本解析的权威顺序在 `OptionRuntimeService.schema()`（实例当前修订版 → 记录的 `form_def_version` 快照 → 当前定义），前端拿不到修订版；而且显示口径一旦有两份实现，页面和导出就会说不一致。所以规则只留后端一份，前端只渲染字符串（净删约 170 行）。

**OCR 复核（`ocr scan`）改了四处做法**：CSV 公式注入（上一版就有的洞）、导出列不能按标签当身份、预览计数漏时间范围（上一版发出去的 bug）、部门子树别在 Java 里展开。

### 本机 docker 栈实测（`--no-cache` 重建后，走 HTTP）

- **通讯录搜部门名**：真实数据里「测试部门2」自己有 1 人、下级「信息部」3 人 → 搜部门名返回 **4**（旧实现只出 1）；「信息部」→3、「财务部」→2、「测试部门3」→1。中间踩过一个坑：终端里的中文关键字被 MSYS 编码搞坏，`--data-urlencode` 发出去的字节不对，看着像"改坏了"——改用百分号编码才见真章（ASCII 关键字一直正常，所以先怀疑了后端）。
- **台账显示文本**：检查项在列里是「不适用 2」、抽屉里逐条「检查项1：不适用 / 检查项2：不适用」；外链下拉字段按钉死的版本回查选项行（本地那天正好 valueColumn == labelColumn，所以标签与值同形，未能在界面上演示"值≠标签"，改用真库用例覆盖）。
- **导出**：默认就是 xlsx（content-type + 文件名 `antflow-form-data.xlsx`），zip 魔数 `PK\x03\x04`，44 行 × 40 列全是 inlineStr（工号 `000003` 保住前导零，`=1+1` 不进公式）；CSV 带 BOM（`EF BB BF`）、中文表头、时间已按 +08 换算、选项名与检查项都是可读文本；`format=bogus` → `EXPORT_FORMAT_UNSUPPORTED`。导出页的"预计 13 行"与时间范围一起变（修掉的那个计数 bug 的直接证据）。

**自评截图**：`.claude/shots/` 的 `contacts-dept-search.png`（左树命中后保留子树 + 右栏 4 人）、`ledger-display-text.png`、`ledger-detail-drawer.png`、`report-export-formats.png`。

**守护用例都验过"去掉修复即失败"**：部门名搜索（去掉谓词 / 把 `<@` 改成 `=` 都红）、台账按版本解释（`COALESCE` 退化成只看当前定义即红）、外链选项名（值列与标签列写反即红）。

### 本轮留档不改（都写进下面的"已知遗留"）

通讯录导出只含当前页（既有）；`admin/FormData` 的 `initialFormDefId` 不随 URL 变化重置筛选（既有）；`/api/users` 无分页且逐行判权、`manager-candidates` 的权限与范围（既有）；角色管理的锁外读旧角色（既有）；行表 GIN 索引（数据源页那条）。

---

## 上一轮：通讯录搜人 / 台账三列 / 报表三个空壳落地

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

## 已知遗留（四轮累计）

- **`npx antd lint ./src` 从来不是干净的**：现在 61 deprecated + 42 usage（`Alert` 的 `message`、`Space direction`、模板页里的静态 `message.xxx` 等）。`frontend/CLAUDE.md` 把它列为提交前必过项，实际不是门槛——新代码与既有写法保持一致，别单独给新文件换风格（否则同一个页面两种写法）。要做就整仓一次性刷。
- 本人提交列表无分页；`status` 无白名单校验（能写进 `APPROVED` 等非法值，报表里落进 `other` 桶）。
- 通讯录的一批既有问题（见上，含"导出只含当前页"）。桌面用户/部门选择「默认填充」与「候选范围」是两件独立配置，范围把本人排除时下拉显示裸 id。**「定位」按钮只判 `deptId` 是否存在**：理论上成员所在部门可能不在左树里（部门权限比人员权限窄），那种情况下点定位会落到一个选不中的部门——按数据范围收窄的口径这条现在到不了，真出现再加。
- 台账 `table_list` 在**单元格**里仍是「N 项」（详情抽屉才逐行展开）；`values()` 里明细行的子字段没做成独立列。
- 「未填写」之外的**空值语义**：`displayText` 为空串时列渲染成破折号，导出是空串——两处一致但含义不同（`—` 只出现在详情）。
- **outbox 投递租约可能重复投递**；**`HIDDEN` 不是保密边界**；前端测试环境关掉了"未处理错误"安全网（`dangerouslyIgnoreUnhandledErrors`）；移动端 lint 既有错误未动；存量角色权限三条（V40）只记录不改；选项行表 GIN 索引（删它要单独一条迁移 + EXPLAIN）。

## 验证基线

后端 `mvn -B test` **520**、前端 `npm test` **280**、移动 `npm test` **348**（本轮未动移动端）；前端 `biome lint` 4 warnings（既有）+ `tsc` 干净。**CI 只跑 `biome lint`（不含格式化与导入排序）——别用 `biome check --write` 全量刷**。新加的守护用例都验过"去掉修复即失败"（ALL 范围漏人、`submitterKeyword` 空集合、报表三种范围、导出范围收窄、部门名搜含下级、台账按版本解释、外链选项名、缺 ffmpeg 的可读报错、非图片字节被拒、媒体值形状是 `id`+`contentType` 的 DTO、上传中/处理中挡住提交）。

> **后端集成用例要在干净库上跑**：`PostgresTransactionalIntegrityIntegrationTest` 里有几条用固定用户名/数据源代码插数据，同一个库跑第二遍会撞唯一键（看着像回归，其实是残留）。本轮的跑法是：容器里建 `antflow_probe`，宿主经一个临时 TCP 转发（`nginx:1.28-alpine` 的 stream 模块，因为 docker 拉不到 socat 镜像）连 `127.0.0.1:15432`，`ANTFLOW_TEST_POSTGRES_URL` 指过去 + `sslmode=disable`（不加会被 SSL 协商噎住）。每轮先 `DROP DATABASE ... WITH (FORCE)` 重建。

> 更早：`63582d5` = S1–S4 整改 + 收尾（决策见 `docs/DECISIONS.md` 的 `D-20260921` ~ `D-20260924-*`）。本轮新增 `D-20260929-export-follows-read-scope`。
