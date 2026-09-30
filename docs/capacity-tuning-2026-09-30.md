# 媒体（图片/视频水印）并发容量与调参

测试时间：2026-09-30 15:30–18:10（Asia/Shanghai）
脚本：`_perf/media-load.mjs` —— **本地文件**（`_perf/` 在 `.gitignore` 里，和 8-24 那轮一样没进仓）；
用法见文末，需要的话用 `git add -f` 单独收进仓。

## 结论（先看这几条）

1. **视频的"并发上限"是配置，不是硬件**：同时最多 `processing-concurrency + processing-queue-capacity` 个在途
   （默认 **2 + 20 = 22**）。第 23 个会被 **拒收并标 FAILED**（"file processing queue is full"）——
   注意它是**上传成功之后**才失败的（行和对象都已落盘，行标 FAILED，客户端可重试）。
   把服务器换成 64 核也一样在 23 个处被拒，只是转码更快。
2. **图片没有"拒收"上限**：48 并发 **48/48 成功**，只是排队（闸门默认 2 路 ffmpeg）。
   延迟随并发线性涨（24 并发 p50 1.09s → 48 并发 p50 2.37s），吞吐卡在 **~12-14 个/秒**。
3. **硬件决定的是速率与内存**：每路 720p 转码 ≈ **1 核 + ~200MB**；实测 2 路并发时容器 anon 峰值
   **1.16GB**（含 768MB 堆），单路 15s/720p 转码 ≈ **1.9s**（宿主基线 2.0s，说明容器内 ffmpeg 没有额外劣化）。
4. **本轮修掉的那个真缺陷**：图片上传原来在**等信号量时占着数据库连接**（`@Transactional` 在方法入口
   取连接）。48 并发时，**其它接口**（GET 元数据）被拖到 **p95/最大 1.97s**。改成"ffmpeg 在事务外、
   只有 DB+存储那段进事务"之后，同一个压测下侧信道 **p95 190ms**（↓ ~10×），图片本身仍然 48/48 成功。

## 测试环境

- 宿主：Intel Core i7-13700F（16C/24T）、31.8GB，Windows + Docker Desktop。
- 压的是 **docker 容器**：`JAVA_TOOL_OPTIONS=-Xms256m -Xmx768m`，同栈还跑着 PostgreSQL 17 / MinIO / nginx；
  容器 cgroup `memory.max` 未设（VM 层约 1.9GB）。
- 数据库连接池：**Hikari 默认 10**（全仓没配置；本轮只在 `compose.yaml` 里把变量名
  `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` / `DB_POOL_MAX_SIZE` 列出来，默认值仍 10）。
- ffmpeg：容器内 Debian ffmpeg 5.1.9 + `fonts-wqy-microhei`（镜像里装的）。
- 客户端：node v24 单进程，`_perf/media-load.mjs`，48 个并发请求。
- 素材：ffmpeg 合成。大图 2400×1800 ≈ 1.3MB/张（每张不同）；小视频 640×360 3-4s ≈ 1.7MB；
  重型视频 1280×720 15s 带噪点 ≈ 18MB。

## 分场景实测

| 场景 | 客户端并发 | 结果 | 单请求延迟 | 吞吐 | 侧信道 GET 元数据 |
| --- | --- | --- | --- | --- | --- |
| 大图带水印 | 24 | 24/24 READY | p50 1.09s / max 1.72s | 13.9/s | max 0.78s |
| 大图带水印 | 48 | 48/48 READY | p50 2.37s / p95 3.65s | 12.6/s | **p95 190ms**（修复前 **1972ms**） |
| 小视频带水印（1.7MB） | 24 | 24/24 完成 | p50 0.32s | 63/s | 111ms（队列没打满：转码太快） |
| 重型视频（18MB/15s） | 24 | **22 READY + 2 FAILED(queue full)** | p50 4.29s（提交） | 5.2/s | 转码期间 p95 104ms |
| 视频排空 | — | 22 个在 17.1s 内清空（p50 8.5s） | ~1.3 个/秒 | — | — |
| 混合（12 大图 + 12 重视频） | 24 | 24/24 最终 READY | p50 1.46s / p95 3.02s | 7.8/s | p95 355ms |
| 容器内存峰值 | — | anon **1.16GB** / 页缓存 0.36GB | — | — | — |

> "侧信道"= 压测期间每 250ms 打一次 `GET /api/mobile/files/{id}`（只读元数据，不碰存储），
> 用来看"上传把数据库连接/线程占成什么样"。它是这份报告里唯一暴露真缺陷的指标。

## 成因

**两个软件闸门（与机器规格无关）**

- 视频：`FileProcessingConfig` 建的是一个 **core=max=`processing-concurrency`、queue=`processing-queue-capacity`、
  `AbortPolicy`** 的线程池。容量就是"在跑 + 在排队"，多出来的直接 `RejectedExecutionException` →
  行标 FAILED（原因写进 `failureReason`，客户端能看到）。
- 图片：`MobileFileService` 里一个信号量（**本轮从写死 2 改成可配** `image-watermark-concurrency`），
  它只限制"同时几个 ffmpeg"，不拒收——所以图片永远不失败，只是越排队越慢。

**硬件决定的部分**

- 转码速率 ≈ 1 核/路（15s 720p `veryfast` 约 1.9s，2 路并发时两路共享 CPU）。
- 内存：每路 720p 转码约 200MB（输入+输出+编码器缓冲，ffmpeg 是 JVM 的**子进程**，算在同一个 cgroup 里）。
- 因此**整容器内存 ≈ 堆 + 转码并发 × 200MB + 400MB**（metaspace/线程栈/页缓存）。

## 按规格配置（8 核 16G 是本次目标）

| 规格 | `processing-concurrency` | `processing-queue-capacity` | `image-watermark-concurrency` | Hikari 池 | 堆 | 在途视频上限 |
| --- | --- | --- | --- | --- | --- | --- |
| 2C 4G（默认） | 2 | 20 | 2 | 10 | 1G | 22 |
| 4C 8G | 2-3 | 20-30 | 2 | 10-12 | 1.5-2G | 22-33 |
| **8C 16G** | **4** | **40** | **3** | **15** | **2-3G** | **44** |
| 16C 32G | 6-8 | 60-80 | 6 | 20 | 4-6G | 66-88 |

- 视频并发先按"**核数的一半**"起（要给图片水印、HTTP 线程、PostgreSQL 留核），再看 p95 与容器内存微调。
- 图片并发 = "**核数的一半左右**"，但它和视频共用同一批核：视频压满时图片会明显变慢（混合场景 p50 从 1.09s 涨到 1.46s）。
- 队列给 `10 × 并发`（默认就是 20/2 的比例）：队列太长只是把失败推迟，用户等待更久。
- Hikari 池 ≥ "同时打 DB 的请求数"；它与转码并发**无关**（转码不占连接，本轮修的就是这件事）。
  池子过小的症状不是报错而是**其它接口被拖慢**——用上面的侧信道指标量。

对应环境变量（都已有默认值，只列要改的）：

```
MOBILE_FILE_PROCESSING_CONCURRENCY=4
MOBILE_FILE_PROCESSING_QUEUE_CAPACITY=40
MOBILE_FILE_IMAGE_WATERMARK_CONCURRENCY=3
DB_POOL_MAX_SIZE=15            # → spring.datasource.hikari.maximum-pool-size
JAVA_TOOL_OPTIONS=-Xms1g -Xmx3g   # compose.yaml 里现在是 -Xms256m -Xmx768m
```

## 怎么复现

```bash
# 图片：48 并发带水印 + 侧信道探测（fileId 用任意一个自己能读到的文件）
node _perf/media-load.mjs submit --files img1.jpg,img2.jpg,... --concurrency 48 \
  --watermark true --probe <fileId>

# 视频：24 个并发提交，然后轮询到终态（看 22 接受 / 2 拒收 与排空时间）
OUT=$(node _perf/media-load.mjs submit --files v1.mp4,... --mime video/mp4 \
  --concurrency 24 --watermark true)
node _perf/media-load.mjs poll --ids "$(echo "$OUT" | grep '^ids=' | cut -d= -f2-)" --timeout 240
```

脚本自己负责登录/CSRF；`--probe` 会一边压一边量元数据接口的延迟，`poll` 会打印每个文件的完成时刻。

## 口径说明（别把绝对值当生产数）

- 压的是**本机 docker**（VM 约 1.9GB），不是生产机；容器 `-Xmx768m` 也不是 16G 机器该用的配置。
- 客户端与服务器同机、单进程 node，网络开销被忽略：**延迟绝对值偏乐观，比例关系可信**。
- 素材是合成视频（`testsrc2`+噪点）。真实手机视频码率更高、运动更复杂，转码时间会更长，
  所以"每路 ≈1 核 + 200MB"应按 **720p 中低码率**理解；1080p/4K 要另测。
- 所有"上限"都是**配置**推出来的，因此这份报告的可复用处是公式与侧信道方法，不是某个具体数字。
