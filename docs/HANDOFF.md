# 任务交接

- **State:** active
- **更新时间：** `2026-10-08T10:30:00+08:00`
- **分支：** `feat/contacts-report-export`（从 `master` = `4328845` 拉出，未推）。前两轮都已合并进 master：**PR #2** = 实测反馈 17 条、**PR #3** = 选项数据源按 demo 重排。

---

## 最新一轮：历史遗留清理（第 23 个视频不再 FAILED / 队列语义 / 前端该拦没拦）

你点名两件事：**清理历史遗留** + **"23 个以上在途时第 23 个上传成功却 FAILED"**。先订正了两条我自己
写错的记录（`t_mobile_file.watermark_text` 其实**早就在落库**、空值语义也已经是 `—`），确认"遗留"里
真正还开着的分四块。队列语义按你选的 **永不拒收** 落地：行留在库里保持 `PROCESSING`，任何一个转码
完成就立刻认领一条补位；reaper 只当崩溃恢复的兜底。

| 提交 | 内容 |
| --- | --- |
| `50d395a` | P0 文档订正（上面那两条过期记录） |
| `a849f28` | P1 **队列语义**：删掉 `RejectPolicy` 整个枚举，池满时 `releaseProcessingClaim`（保持 PROCESSING 等空位）；任务 `finally` 里 `reap(1)` **完成即泵**；客户端轮询 600→1800 次（30 分钟）；顺带 `ftyp` 分支不再把 `heic/mif1/msf1/avif/avis` 判成 `video/mp4`；`<?xml` 收紧成"声明之后必须真出现 `<svg`"；删掉没人调的 `findReadyDuplicate` wrapper |
| `e48e4ee` | P2 后端四条：通讯录导出改走后端（`GET /api/users/export`，同一条 `listAuthorized`，不再只导当前页）、本人提交列表分页（`limit/offset` + `countMySubmissions`）、`status` 白名单、台账 `table_list` 单元格出首行 |
| `506fcf8` | P3 前端"该拦没拦"五条：可见性改记忆化 DFS（条件引用后声明的字段不再误判）+ 数值比较先拒空值；明细表逐行校验 + 行数上下限 + 行内隐藏列不算"还在传"；检查项逐项校验（含照片上限，两端都补）；桌面视频 `maxDuration` 真拦；扫码卸载时 abort |
| `c615061` | P4.2 outbox 三处：幂等键改成 `type:instanceId:event.id()`（不再静默丢"再次通知"）、投递期间只续**自己持有**的租约、webhook 带稳定事件键 |
| `437eda6` | P4.3 上传改"先插行后写对象" + 孤儿对象清扫器（默认只记日志） |
| `4f63601` | P4.1 收回 vitest 的 `dangerouslyIgnoreUnhandledErrors`（未处理错误重新算红） |
| `37e1cf3` | P4.5 antd 弃用只清理 17 处语义等价的；另 44 处**实测不能自动改**（见下） |
| `da8360f` | P4.6 V52 删掉用不到的 GIN 索引（先 EXPLAIN） |

**验证**：后端 `mvn -B test` **556 绿**（基线 544）、前端 `npm test` **288 绿**（基线 280）、移动端
`npx vitest run` **351 绿**（基线 350）；`biome lint` 只有既有的 3 条 warning，`tsc` 干净。每条守护都
验过"去掉修复即失败"：P1 的 `uploadLeavesRowProcessingWhenQueueIsFull` / `completedVideoPumpsOneQueuedRow`、
P2 的 9 条（`UserExportControllerTest` 6 + `FormDataServiceTest` 2 + `FormValueDisplayTest` 1）、P3 的 11 条、
P4.2 的 3 条（webhook 那条用真 HTTP server 收）、P4.3 的 4 条。P4.5 是纯改名，靠 288 条用例兜。

**踩到的一个大坑（值得单说）**：`npx antd lint` 建议把 `Select` 的 `onSearch`/`filterOption` 迁进
`showSearch={{…}}`，但当前依赖里**这个对象形式是空操作**——antd 6.5.3 + rc-select 14.1.18 只把
`showSearch` 当布尔用，没有任何地方读 `showSearch.onSearch`。我照提示改了一版，`AssigneePicker` 的
搜索用例当场红（关键字永远停在空串）：**"照着 lint 提示改"会把搜索静默改坏**。所以这次只做"语义等价
且用例能兜住"的 17 处，其余连同理由写进 `D-20260930-antd-deprecations-that-cannot-be-migrated-yet`。

**真机回归（决定性证据，镜像重建后）**：24 个 ~10MB / 720p / 带水印的视频**并发 24 提交**——
**24/24 HTTP 200 PROCESSING**（修复前是 22 + 2 FAILED），排空后 **24/24 READY、0 FAILED、0 PROCESSING**
（第 23、24 个不再被拒）。MinIO 侧对账：`video` 行 138 = 对象 138、**孤儿候选 0**；`image` 侧 126 行
对 124 对象（缺的两个是上一轮手工 curl 造的行，读路径会自愈重写，与本轮无关）。V52 在真库上
`Successfully applied 1 migration ... now at version v52`，启动探测
`媒体水印就绪：ffmpeg='ffmpeg'，字体='/usr/share/fonts/truetype/wqy/wqy-microhei.ttc'` 正常。

**这轮没做**：P4.4 换静态 ffmpeg 把镜像从 1.57GB 压到 ~1.2GB——它的退出条件是"重建后**实测**镜像大小
+ 启动探测 + 中文水印渲染"三条都过，本轮只重建了镜像（1.57GB 未动），没有动 Dockerfile：宁可不做，
也不提交一个没验证的 apt→tarball 改动。孤儿清扫器目前仍是 dry-run（默认不删），要在真机看一轮日志
（上面对账的"孤儿候选 0"说明现在没什么可删）再决定开不开真删。

---

## 上一轮：媒体并发（图片不再占着连接等信号量 / 闸门可配 / 移动端失败可见）

你让压一遍并发，于是有了 `docs/capacity-tuning-2026-09-30.md`。压测把"两个上限"分开了：视频的 22 个
（`processing-concurrency + queue`）和图片的"2 路闸门"**都是配置**，换更大的机器也是在同一个地方失败，
只是更快；**硬件决定的是速率与内存**（每路 720p 转码 ≈ 1 核 + 200MB，2 路时容器 anon 峰值 1.16GB）。
压测同时暴露了**唯一一个真缺陷**：图片上传在**等信号量**时占着 Hikari 连接（10 个连接被排队者占满），
别的接口一起被拖 —— 48 并发时 GET 元数据 p95/最大 **1.97s**。

| 提交 | 内容 |
| --- | --- |
| `5d24fcf` | ① `upload` 去掉 `@Transactional`，只把"去重（含行锁）→ 写对象 → 插行 → 视频登记 afterCommit 入队"放进 `TransactionTemplate`（ffmpeg 与信号量等待落在事务外）；显式构造替代 Lombok（信号量要从配置建，Lombok 的字段初始化早于构造体）。**①b** 去重读改走显式 `@Select`：`FOR UPDATE` 锁不住不存在的行，并发首次上传同一份字节会留重复行、`selectOne` 命中多行抛 `TooManyResultsException` → 下一次上传 500，现在 `ORDER BY created_at, id LIMIT 1` 固定取最早那行。**②** 图片闸门从写死改成 `image-watermark-concurrency`（默认 2）；图片水印**成品**覆盖回来后再核一次大小上限（视频那条有、图片这条漏了）；`compose.yaml` 列 Hikari 池变量名；顺手清两个未用 import 与一处失效的 `{@value}` |
| `2272518` | ③ 移动端把 `failureReason` 透出来（原来统一成一句"视频处理失败"）+ 检查项照片失败后保留文件并给"重试"（桌面端早就有，移动端这条路没有） |
| `docs/capacity-tuning-2026-09-30.md` | 容量报告；压测脚本是**本地** `_perf/media-load.mjs`（`_perf/` 在 `.gitignore` 里，和 8-24 那轮一样没进仓 —— 要收进仓得 `git add -f`）。报告里用到的侧信道指标（压测期间量 GET 元数据的延迟）是这轮唯一暴露真缺陷的量 |

**验证**：后端 `mvn -B test` **544 绿**（基线 540）、移动端 `npx vitest run` **350 绿**（基线 348）。
四条新守护都验过"去掉修复即失败"：ffmpeg 不在事务里而 insert 在事务里（`isActualTransactionActive`，
配一个 ~12 行的假事务管理器）；闸门=1 时两个并发上传串行；成品超限 `BAD_FILE` 且不写存储；
真库两行同 `(owner_id, sha256)` 时上传返回最早那行而不是 500。

**真机复压（镜像重建后）**：48 并发带水印大图 **48/48 成功**、侧信道 GET 元数据 **p95 190ms**
（修复前 1972ms，↓10×）；24 个重型视频仍然 **22 READY + 2 FAILED("queue is full")**、22 个在 17.1s 排空、
anon 峰值 1.16GB —— 与修复前一致（视频路径没动，属回归确认）。

**两个坑记下来**：① MP 的 `QueryWrapper` 会把 `last()` 拼在 `ORDER BY` **之前**、再自己追加 `LIMIT 1`，
所以 `orderByAsc(...).last("LIMIT 1 FOR UPDATE")` 生成的是 `FOR UPDATE ORDER BY ... LIMIT 1`（非法 SQL），
真库用例当场 `BadSqlGrammar` —— 要精确控制 SQL 就写显式 `@Select`（和 `selectByIdsForUpdate` 一致）。
② compose 与 Spring 的默认值语法不同：compose 要 `${VAR:-default}`，`${VAR:default}` 会让 `docker compose build` 只打个
"escape any $" 的警告就停住。

**OCR 复核（这轮 `scan` 恢复了，7 条 / 3m40s）**：4 条核实为真并已折进上面（成品大小、去重重复行、
两个未用 import、失效 `{@value}`）；1 条**核实后已被现有缓解**：`detectImageContentType` 把任何 `<?xml`
开头的文本判成 `image/svg+xml`，但 `/content` 无条件带 `Content-Disposition: attachment`（`MobileFileController:61-64`），
直接打开那个 URL 只会下载、不会在本站源里渲染 → `<svg onload>` 跑不起来，所以**没加**那行 CSP
（真要做是加固，不是修洞）。1 条当时**留档不改**：`ftyp` 品牌回退把 `heic/mif1/avif` 判成 `video/mp4`
（要客户端谎报 mime 才踩到）——**已在下一轮的 P1 修掉**（`detectContentType` 对这些品牌返回 null）。

**不在本轮**：自适应限流/背压、图片水印异步化、
把 MinIO `put` 移出事务（回滚会留孤儿对象，而且行锁必须覆盖 put）。

---

## 上一轮：附件上传的去重竞态 + 大小上限可绕过

上一轮修完"删除 × 关联"和水印链路后，留档里剩的两条**同族**问题（都是"检查与改动之间没有保护"）本轮做掉，另外把自己 review 出来的一个假设加固了。

| 提交 | 内容 |
| --- | --- |
| `d95a746` | **去重走行锁 + 对象在就不重写**：`findReadyDuplicate` 加 `FOR UPDATE` —— 并发 `delete`（169d2d3 起走行锁）会插进"查到重复行"和"返回/补写对象"之间，让客户端拿到一个已删文件的 DTO（之后提交被 `append` 以 `FILE_NOT_FOUND` 拒整单），对象还会被重新写回去变成孤儿。PG 在 READ COMMITTED 下锁等待结束后会重新求值谓词，已提交的删除行不再满足 `status='READY'` → 自动走"新建一行"，**不需要额外的复核分支**。`FileStorage` 新增 `exists(String)`（**故意不抛受检异常**）：对象在就只返回旧行（同一份字节不再往 MinIO 重写一遍），不在才补写。**大小上限改看真实字节数**：`validateBasic` 比的是客户端声明值，而 `stage()` 数出的真实值没人用 —— 声明小、实际大就能绕过 `maxBytes`/`maxVideoBytes`（代价落在磁盘）→ 落盘后按同一口径（抽出的 `sizeLimit`）再比一次。 |
| `4492da7` | 守护用例：对象在就不重写（`putCount==0`）、声明大小撒谎被真实字节数挡住、存储探针失败要原样失败成 `FILE_STORAGE_FAILED`（不能退化成"缺失"白重传，也不能被外层 `catch(IOException)` 吞成 `BAD_FILE` 甩锅给用户的文件）；真库"另一事务持锁把重复行标 DELETED 后再上传同一份字节 → 必须新建一行"。 |

**自己 review 抓到的一处**（OCR 这轮跑不起来，见下）：`statObject` 是 HEAD 请求、各家错误码并不统一（`NoSuchKey` / `NotFound`），只认字符串码有把"缺失"误判成"查不动"的风险 —— 那会让**每次重复上传都直接失败**，比反向误判（白写一份同字节对象）糟得多。改成 `NoSuchKey`/`NoSuchBucket`/`NotFound`/HTTP 404 都算缺失，其余（权限、签名、网络抖动）抛 `FILE_STORAGE_FAILED`。

**验证**：`mvn -B test` **540 全绿**（基线 535）。四条守护都验过"去掉修复即失败"：去掉 `FOR UPDATE` → 真库那条报「去重把已删文件还给了客户端（没走行锁）」；去掉 `exists` 判断或真实大小检查 → 三个单测分别挂在 `putCount` / "成功上传" 上。**真机**：重建镜像后同一张图连传两次 → **返回同一个 id，且 MinIO `image/` 对象数只 +1**（24→25）—— 说明探针确实命中了"对象在"这条分支（错误码/404 判断没问题，也没有重写）。

**OCR 这轮没跑成（上游供应商的问题，不是 OCR 的）**：`ocr scan` 的完成请求被 model 网关（`api.cdn-krill-ai.com/coding/v1`，`openai-responses` 协议）回成 `text/event-stream`，而客户端只会解 JSON。非流式的 `ocr llm test` 是通的；`protocol openai` 换过去返回空响应；`deepseek` 那条 402（余额不足）；1.12.11 → 1.12.10 同样失败 → **不是 OCR 版本回归**。下次要过这道门槛，得先换一个支持非流式（或 SSE 兼容）的 provider。本轮由人工 review 代替（上面那条 `statObject` 就是人工审出来的）。

**这一族里剩下的**（本轮不做）：上传仍是"先写对象、后插行"（回滚会留孤儿对象）；没有孤儿对象清扫器（所以上面那条"对象在就不重写"其实也承担了唯一的自愈兜底）；`(owner_id, sha256)` 没有索引（去重是扫该 owner 的行）。

---

## 上一轮：媒体链路后端健壮性（堆里不再有整份视频 / 真租约 / 删除与关联竞态）

上一轮把"缺 ffmpeg 的 422"和"桌面端四个控件从不上传"修了，并在留档里列了四条后端并发/事务问题。这一轮**只做那四条里会真的伤人的部分**（你圈的范围），先把方案交给 OCR 复核，它把我草稿里的三处硬伤改掉了（下面「OCR 改掉的做法」）。

| 提交 | 内容 |
| --- | --- |
| `8110b85` | **流式化**：`MediaWatermarkProcessor.apply(byte[])` → `applyTo(Path input, Path workDir, …)`（workDir 由调用方建删），`tailOf` 只读日志末尾 8KB（原来 `readAllBytes` 整份日志）；`MobileFileService` 视频改成"源对象流式落盘 → ffmpeg 读文件写文件 → 流式传回存储"，图片分支也不再先把 staged 读进堆（`synchronized` 只串行 ffmpeg，不拦内存——等待者原来各持整份 byte[]）；加 `sha256(Path)`；转码后成品超过 `maxVideoBytes`（默认 100MB）直接失败并写明"转码后的视频超过大小上限"，别把超大对象塞进存储；图片同步链路加并发闸门（默认 2）护住请求线程与磁盘 |
| `6a65b1b` | **租约 + 独立 key 发布 + 有界恢复**：`V51` 给 `t_mobile_file` 加 `processing_claim_token` / `processing_claimed_at`（实体**不加**这两个字段，`updateById` 就不会回写它们）；`claimProcessing/renewProcessingClaim/releaseProcessingClaim/claimStaleProcessing/publishProcessed/failProcessing` 全是**按 token 条件**的手写 SQL；发布写**本次尝试独有的 key**（`原 key + ".wm-" + token`）再用条件 UPDATE 把行的 `storage_key` 指过去（原地覆盖会在"put 成功但 DB 没更新"时变成二次水印、原片还没了）；租约在下载后/ffmpeg 前/ffmpeg 后各续一次；`catch (Throwable)` 不再让 `OutOfMemoryError` 把行永远卡在 PROCESSING（写 FAILED 后**重抛 Error**）；`recoverPendingVideoProcessing` 改成 `@Scheduled` 的 `reapStaleProcessing()`：有界（`processingReapLimit`，默认 20）、先 claim 拿 token、只领"未领取或已过期"（`processing_claimed_at IS NULL OR < staleBefore`——只写 `<` 会永远捞不到迁移前的遗留行），队列满时**上传路径标 FAILED / 恢复路径只还租约**（前者客户端在轮询要立刻有答案，后者下一轮还能再捞） |
| `6f0e8a3` | **关联**：`normalized` 先按 `fileId` 去重（`t_form_data_file` 主键是 `(form_data_id, file_id)`，上传端 SHA 去重会让两个字段的同一张照片落到同一个 id——按 `fileId+fieldId` 去重就两次插同一主键、整单 500），再用**一条按 id 升序的批量 `FOR UPDATE`** 加锁并在锁内复核 READY/`deleted_at`/owner；`insertFileLink` 冲突改 `ON CONFLICT DO UPDATE SET field_id = EXCLUDED.field_id`（`DO NOTHING` 会把 `reconcileEditable` 的"受限→可编辑"迁移静默吞掉）；`requireReadable` 给 admin/owner 提前 return（一页 20 张图原来无条件逐关联实例判权） |
| `169d2d3` | **删除**：先按 id 加 `FOR UPDATE`（否则"检查有没有被提交"与提交方"检查是否 READY"交错 → 提交成功但附件指向已删文件）；`PROCESSING` 拒绝删除（「视频正在加水印，处理完再删」）；对象删除挪到 `afterCommit`（提交前删一旦回滚就是"READY 行 + 对象已丢"，反过来最多是个没人引用的孤儿对象） |
| `6fad747` | **自测发现的洞**：`MobileFileLinkService` 自己没有事务，`FOR UPDATE` 在自动提交下等于没锁——现有调用方（`start`/`FormDataService.submit`）都在事务里所以线上没炸，但这是"靠调用方记得开事务"的隐形契约。类上加 `@Transactional(REQUIRED)` |
| `0dae42a` | **真库守护**（这些 SQL 是手写的，mock 掉 mapper 只能断言"调了哪个方法"）：遗留 NULL 行必须能领到 / 已认领不重复领 / 续租后不被抢走 / 真过期能重新领；`publishProcessed` token 不对影响 0 行；删除×关联竞态的终态；同一文件两个字段只 1 行且 `field_id` 是后插的权威分类；处理中不给删 |

**OCR 改掉的做法（草稿 → 现在）**：① 转码结果**不能**原地覆盖原 key（崩溃窗口下会二次水印 + 丢原片）→ 独立 attempt key + 条件发布，失去租约的那次只清理自己那份；② 只有 `processing_claimed_at` + "任务开头续一次"不是有效租约（排队 + ffmpeg 最长 10 分钟 + MinIO 上下行都能超过 stale 窗口）→ claim token + 处理期间续租 + 终态/释放都按 token 条件更新，丢租约的 worker 不许发布结果；③ 我原打算把 `Cache-Control` 从 `no-store` 改成 `private, max-age=3600` —— **撤掉**，只做 admin/owner 短路；④ `catch (Throwable)` 不能吞 Error。

### 真机 + 用例矩阵验证

- **`mvn -B test` 535 全绿**（基线 520，只碰后端；真库类 86 条）。
- **多个大视频并发不再 OOM**（本轮最有说服力的一条）：容器 `-Xmx768m` 里同时传 2 个带水印视频（67MB + 61MB，`video/mp4`）→ 两个都 `PROCESSING` → `READY`（67MB→33MB、61MB→8.9MB，ffmpeg 真重编码了）；采样内存**全程 ~449MB / 上限 768MB 堆**，日志无 `OutOfMemoryError`，`0` 行 FAILED。抽帧看到的右下角水印是「现场检查 2026-09-30 07:48」——**中文渲染正常**。
- **发布后旧对象真的清了**：MinIO `mobile` 桶 `video/` 前缀里只有 3 个 `.wm-<token>` 新对象，本轮上传的 3 个原 key **都不在了**（没有孤儿）。
- **workDir 清干净**：转码完成后容器里 `ls /tmp | grep -c antflow-` = `0`。
- **处理中删除被拒**：`DELETE /api/mobile/files/{id}` → **422 `BAD_FILE_STATE`「视频正在加水印，处理完再删」**，且该文件随后正常跑完 `READY`（没被删坏）。
- **移动端 5S 那种照片仍 200 + 带水印**：JPEG（模拟手机拍的照片，`image/jpeg`）上传 → 200、`READY`、6.6KB→11.9KB，水印文字与中文都正常。
- **`V51` 在真库上迁移成功**：启动日志 `Migrating schema "public" to version "51 – mobile file processing claim"` → `Successfully applied 1 migration`，无报错。

**两件要说清楚的代价/结果**：

1. **磁盘成了新的约束**（替代原来的堆约束）：一次视频转码峰值同时有 staged + input + output 三份文件，2 并发就是 6 份。这个容器没撑爆，但如果将来放开并发数或视频更大，得先看可写层容量。`finally` 保证 workDir 一定清（成功/失败/超时三条路径）。
2. **图片水印仍占着请求线程与 Hikari 连接**（内存问题已由流式化解决，但同步语义没动）。真要做得挪出事务，本轮没做。

### 本轮留档不改（新发现，都不属于"会真的伤人"那条线）

- 上一轮列的前端既有问题（`displayConditions` 可见性依赖遍历顺序、`table_list` 不逐行校验、检查项无 `validate`、`maxDuration` 运行时不校验）都还在。
- 移动端检查项照片仍不传水印参数（检查项节点没有水印开关）。

### 验证踩坑（省下一轮的时间）

- **本机 docker 的"点了没反应"**：`docker start/inspect/run` 卡住（>25s 无输出）不是慢，是那个 **probe 容器对象把 daemon 卡住了**；先把挂着的 `docker.exe` CLI 进程 `taskkill`、`docker rm -f` 掉那个容器就好了。纯 `docker run alpine` 能跑说明 daemon 本身没问题。
- **集成库不必再走 docker 探针**：本机 5432 就有活的 PostgreSQL（`postgres/Tao@1234`，见 `application.yml` 注释），直接 `CREATE DATABASE antflow_probe` + `ANTFLOW_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/antflow_probe` 就行，比之前那套 nginx stream 转发省事（`mongodb`/`psql` 都没有，用 JDBC 建库最简单）。
- **MSYS/Windows 上 curl 上传大文件**：`-F "file=@/tmp/x.mp4;type=video/mp4"` 会被 MSYS 的路径转换搞坏（`curl: (26) Failed to open/read local data`）→ 用**相对路径**（先 `cd` 到文件目录）或 `MSYS_NO_PATHCONV=1`；`-F "watermarkText=中文"` 会被转成 ANSI 码页（渲染出来是豆腐块，**不是**应用 bug）→ 用 `-F "watermarkText=<文案.txt"` 从 UTF-8 文件读。

---

## 上一轮：水印缺 ffmpeg 的 422 + 桌面端媒体组件从来没上传

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

- **服务端并发/事务**：~~SHA 去重会覆盖旧对象却返回旧行~~（最新一轮已修）；**先写 MinIO 后插行，回滚留孤儿对象**（还在）；~~`delete` 在事务提交前删对象~~（已修）；~~`countLinks` 与删除不原子~~（已修）；~~`processVideoWatermark` 无独占认领、与 `delete` 竞态可把已删对象写回来~~（已修）；~~`stage()` 只信 `MultipartFile` 声明的大小~~（最新一轮已修）。
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

## 已知遗留（八轮累计）

- **`npx antd lint ./src` 不是干净的，而且短期清不掉**：清理后是 44 deprecated + 45 usage。其中
  **Select 的 `onSearch`/`filterOption`（13 处）在当前依赖里没有安全改法**——`showSearch={{…}}` 这个对象
  形式是空操作（antd 6.5.3 + rc-select 14.1.18 只把 `showSearch` 当布尔用），改了会把搜索静默改坏；
  `Space direction`（28）与 `Drawer width`（3）是布局语义变化，要做必须配视觉验证；静态
  `message.*`/`Modal.confirm`（41）要先做"模块级实例注入"。理由详见
  `D-20260930-antd-deprecations-that-cannot-be-migrated-yet`。新代码与既有写法保持一致，别单独换风格。
- **P4.4 镜像瘦身没做**：后端镜像还是 1.57GB（apt 的 ffmpeg 拖进 mesa/llvm）。换静态构建的退出条件是
  "重建后实测镜像大小 + 启动探测 + 中文水印渲染"三条都过——本轮重建过镜像、只验了后两条，没动
  Dockerfile：宁可不做也不提交一个没验证的 apt→tarball 改动。
- **孤儿清扫器目前是 dry-run**（`antflow.mobile.files.orphan-sweep-delete` 默认 false，只记日志）：
  要在真机看一轮"它打算删什么"再决定开不开。
- **webhook 是 at-least-once**：接收端要幂等请用 `X-AntFlow-Event-Key` / body 的 `eventKey`。
- 通讯录剩下的一批（**导出只含当前页**已修）：部门移动后不刷新成员、删空末页不回位、负责人候选只取
  100 人且不可键盘操作、清空上级不持久化、凭据入口只按 admin 显示。桌面用户/部门选择「默认填充」与
  「候选范围」是两件独立配置，范围把本人排除时下拉显示裸 id。**「定位」按钮只判 `deptId` 是否存在**：
  理论上成员所在部门可能不在左树里（部门权限比人员权限窄），那种情况下点定位会落到一个选不中的部门
  ——按数据范围收窄的口径这条现在到不了，真出现再加。
- 台账 `values()` 里明细行的子字段没做成独立列（单元格已改成出首行）。
- 「未填写」之外的**空值语义**（已订正）：导出取的是 `detailText`，空值已经是 `—`；只有"**记录里根本
  没有该字段**"才落空串（`FormDataExport.model` 的 `getOrDefault(fieldId, "")`）—— 与列上的
  `displayText || '—'` 是同一种口径，不再是两回事。
- **`HIDDEN` 不是保密边界**（已写成"明确不做"，见 `D-20260930-hidden-is-not-a-secrecy-boundary`）；
  移动端 lint 既有 warning 未动；存量角色权限三条（V40）只记录不改。
- 视频积压时**没有进度 UI**（只能靠客户端轮询等）——队列语义已经是"永久排队不失败"，但用户看不到
  "排在第几位"。真正的解是任务进度，记在这里。

## 验证基线

后端 `mvn -B test` **556**、前端 `npm test` **288**、移动 `npx vitest run` **351**；前端 `biome lint` 3 warnings（既有）+ `tsc` 干净；`npx antd lint ./src` 44 deprecated + 45 usage（见遗留）。**CI 只跑 `biome lint`（不含格式化与导入排序）——别用 `biome check --write` 全量刷**。新加的守护用例都验过"去掉修复即失败"（ALL 范围漏人、`submitterKeyword` 空集合、报表三种范围、导出范围收窄、部门名搜含下级、台账按版本解释、外链选项名、缺 ffmpeg 的可读报错、非图片字节被拒、媒体值形状是 `id`+`contentType` 的 DTO、上传中/处理中挡住提交；媒体健壮性那轮：删掉 ④⑤ 的修复代码后 `MobileFileServiceTest`/`MobileFileLinkServiceTest` 挂 9 条、真库 `deleteRefusesAFileThatIsStillBeingProcessed` 与 `sameFileInTwoFieldsIsLinkedOnceWithTheAuthoritativeField`（复现出原主键冲突 `DuplicateKey`）也挂；**最新两轮**：去掉 `FOR UPDATE` → 真库 `uploadInsertsFreshRowWhenDuplicateIsDeletedWhileLocked` 挂；去掉 `exists` 判断/真实大小检查 → 3 个单测挂；把 `@Transactional` 加回 `upload` → `imageWatermarkRunsOutsideTheTransactionWhileInsertRunsInside` 挂；闸门写死回 2 → 串行那条挂；去掉移动端 `failureReason` 透传 / 关掉 `setFailedFiles` → 两条移动用例挂）。

> **移动端 `npx vitest run` 会带出一条 unhandled error**（`unmountComponentAtNode is not a function`，来自 antd-mobile 的 Popup/rc-util 与 React 19 的卸载路径）。单独跑 `fields.test.tsx` 或 `files.api.test.ts` 都没有 —— 是既有测试之间的干扰，与本轮改动无关，别误判成回归。

> **后端集成用例要在干净库上跑**：`PostgresTransactionalIntegrityIntegrationTest` 里有几条用固定用户名/数据源代码插数据，同一个库跑第二遍会撞唯一键（看着像回归，其实是残留）。本轮的跑法（比之前简单）：本机 5432 的 PostgreSQL 里 `CREATE DATABASE antflow_probe`（宿主没有 `psql`，用 postgres JDBC 驱动跑几行 Java 最省事），`ANTFLOW_TEST_POSTGRES_URL='jdbc:postgresql://127.0.0.1:5432/antflow_probe'` + `ANTFLOW_TEST_POSTGRES_USERNAME=postgres` + `ANTFLOW_TEST_POSTGRES_PASSWORD=Tao@1234`（`sslmode`/`stringtype` 不用加，测试自己补 `stringtype`）。每轮先 `DROP DATABASE ... WITH (FORCE)` 重建——**一次失败留下的行会让下一轮出现假回归**（本次就撞了 `employee_no`/`option_data_source.code` 唯一键）。

> 更早：`63582d5` = S1–S4 整改 + 收尾（决策见 `docs/DECISIONS.md` 的 `D-20260921` ~ `D-20260924-*`）。`D-20260929-export-follows-read-scope` 起，媒体链路累计新增：`D-20260930-media-watermark-needs-ffmpeg-and-says-so`、`D-20260930-desktop-media-uploads-go-through-the-mobile-file-api`、`D-20260930-video-watermark-independent-key-and-token-lease`、`D-20260930-delete-keeps-the-row-lock-and-deletes-the-object-after-commit`、`D-20260930-form-data-file-is-keyed-by-file-id-not-field`、`D-20260930-upload-dedupe-locks-the-row-and-the-size-cap-counts-real-bytes`、`D-20260930-media-watermark-outside-the-transaction-and-configurable-gates`。
