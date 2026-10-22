package com.antflow.attachment;

import com.antflow.engine.BizException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 视频水印的异步流水线：**认领（租约）→ 入队 → 转码 → 带 token 发布/失败 → 清理旧对象**，
 * 外加启动恢复与定时 reaper。
 *
 * <p>从 {@code MobileFileService} 原样搬出（方法体逐字未改），单独成类的原因只有一个：
 * 这套"租约 + 队列 + 补偿"是自成一体的状态机，和 HTTP 侧的入参校验混在一起没人读得动。
 *
 * <p>**为什么不拆成"排队"和"处理"两个类**：处理任务完成时会 `reap(1)` 把下一条泵进来，而
 * reap 又要入队——两块天然是环。硬拆只能靠互相持有，比现在更难读。
 */
@Slf4j
class MobileVideoWatermarkPipeline {
    private static final String PROCESSING_STATUS = "PROCESSING";

    private final MobileFileMapper fileMapper;
    private final FileStorage storage;
    private final MobileFileProperties properties;
    private final MediaWatermarkProcessor watermarkProcessor;
    private final Executor fileProcessingExecutor;
    private final MediaDurationProbe durationProbe;
    private final MobileFileObjectStore objects;

    MobileVideoWatermarkPipeline(MobileFileMapper fileMapper, FileStorage storage,
                                 MobileFileProperties properties,
                                 MediaWatermarkProcessor watermarkProcessor,
                                 Executor fileProcessingExecutor, MediaDurationProbe durationProbe,
                                 MobileFileObjectStore objects) {
        this.fileMapper = fileMapper;
        this.storage = storage;
        this.properties = properties;
        this.watermarkProcessor = watermarkProcessor;
        this.fileProcessingExecutor = fileProcessingExecutor;
        this.durationProbe = durationProbe;
        this.objects = objects;
    }

    /**
     * 恢复滞留的异步任务。启动时跑一次，之后由 {@link #reapStaleProcessing()} 定时接管——
     * 以前只有启动这一次：进程里 OOM/被杀留下的 PROCESSING 行除了重启没人管。
     */
    void recoverPendingVideoProcessing() {
        reapStaleProcessing();
    }

    /**
     * 定期把"没人认领或租约已过期"的 PROCESSING 行重新排进队列。**有界**（每轮最多
     * {@code processing-reap-limit} 条）且**先认领**：认领不到的说明别人正在跑，直接跳过。
     *
     * <p>被队列拒绝时保持 PROCESSING（{@link #enqueueVideoProcessing}）——原来在启动恢复里被拒就直接标
     * FAILED，一次崩溃留下的积压会有一大半"永久失败"。
     */
    void reapStaleProcessing() {
        int requeued = reap(properties.getProcessingReapLimit());
        if (requeued > 0) {
            log.info("重新排入 {} 个滞留的视频水印任务", requeued);
        }
    }

    /**
     * 认领并排队至多 {@code limit} 条待处理行，返回排进去的条数。
     *
     * <p>三个调用点：启动恢复、定时 reaper，以及**每个转码任务完成时**（`reap(1)`，见
     * {@link #enqueueVideoProcessing} 里包的那层 try/finally）。
     * 最后这个"完成即泵"是关键：队列满时上传的视频不再被标 FAILED，而是留在库里等 —— 靠它把排队里的
     * 下一条**立刻**接上，而不是干等下一轮定时（默认 60s）。
     */
    private int reap(int limit) {
        OffsetDateTime staleBefore = OffsetDateTime.now()
            .minusMinutes(properties.getProcessingStaleMinutes());
        // 这一轮的租约 token：一批行共用一个（它们本来就属于同一轮调度），拒绝时按它整体归还。
        UUID reapClaim = UUID.randomUUID();
        java.util.List<UUID> claimed = fileMapper.claimStaleProcessing(staleBefore, limit, reapClaim);
        // claimStaleProcessing 已经把同一批的租约写成一个 token 了，这里只负责入队；
        // 被队列拒绝就 release 这个 token 等下一轮（不会误伤别人的租约）。
        claimed.forEach(id -> enqueueVideoProcessing(id, reapClaim));
        return claimed.size();
    }

    void processVideoWatermark(UUID id, UUID claim) {
        MobileFile file = fileMapper.selectById(id);
        if (file == null || !PROCESSING_STATUS.equals(file.getStatus())) return;
        Path workDir = null;
        Path published = null;
        try {
            // 源对象流式落到临时文件，ffmpeg 读文件、把成品写文件，再流式传回对象存储：
            // 整个过程堆里只有拷贝缓冲（原来 readAllBytes + apply(byte[]) 是 2~3 份整视频）。
            workDir = Files.createTempDirectory("antflow-video-watermark-");
            Path input = workDir.resolve("input" + MediaWatermarkProcessor.extensionFor(
                file.getContentType()));
            try (InputStream source = storage.get(file.getStorageKey()).getInputStream()) {
                Files.copy(source, input);
            }
            renewClaim(id, claim);
            Path output = watermarkProcessor.applyTo(input, workDir, file.getContentType(),
                file.getWatermarkText());
            long size = Files.size(output);
            long limit = properties.getMaxVideoBytes();
            if (size > limit) {
                // 输入上限只管源文件：转码后的成品可能更大（码率/封装变了），别把超大对象塞进存储。
                throw new BizException("WATERMARK_PROCESSING_FAILED",
                    "转码后的视频超过大小上限（" + (size / 1024 / 1024) + "MB > "
                        + (limit / 1024 / 1024) + "MB），请换更小或更短的视频。");
            }
            renewClaim(id, claim);
            // 结果写**独立 key**，不覆盖原对象：原地覆盖的话，put 成功而 DB 没更新（崩溃/租约丢）
            // 会留下"行还是 PROCESSING、对象已经是成品"的状态，重跑一次就是二次水印，出错时原片也没了。
            String resultContentType = watermarkProcessor.resultContentType(file.getContentType());
            String attemptKey = file.getStorageKey() + ".wm-" + claim;
            try (InputStream processed = Files.newInputStream(output)) {
                storage.put(attemptKey, processed, size, resultContentType);
            }
            published = Path.of(attemptKey);
            int updated = fileMapper.publishProcessed(id, claim, attemptKey, resultContentType, size,
                MobileFileStaging.sha256(output), MobileFileStaging.toMp4Name(file.getOriginalName()),
                durationProbe.seconds(output));
            if (updated == 0) {
                // 租约已不在自己手上（被 reaper 抢走 / 行被删）：把自己的成品丢掉，绝不碰这一行。
                log.warn("视频水印结果被丢弃：租约已失效（file={}）", id);
                storage.delete(attemptKey);
                published = null;
                return;
            }
            cleanupOldObject(file.getStorageKey());
        } catch (Throwable failure) {
            // 这里连 Error 一起收：OOM 时原来没人接（catch(Exception) 接不住），行会永远停在 PROCESSING。
            // 先尽量把状态写成人话，再把 Error 抛回去——**不吞**（吞掉 ThreadDeath/LinkageError 会更糟），
            // 而且写库本身也可能失败，所以 reaper 才是兜底。
            log.error("视频水印处理失败：{}", id, failure);
            fileMapper.failProcessing(id, claim,
                MobileFileStaging.truncate(failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage()), 512));
            if (published != null) {
                try {
                    storage.delete(published.toString());
                } catch (Exception ignored) {
                    // 已经失败一次了，孤儿对象交给人工/后续清理
                }
            }
            if (failure instanceof Error error) throw error;
        } finally {
            MediaWatermarkProcessor.deleteRecursively(workDir);
        }
    }

    private void renewClaim(UUID id, UUID claim) {
        if (fileMapper.renewProcessingClaim(id, claim) == 0) {
            // 租约没了：继续跑只是浪费 CPU，最终 publish 也会被条件更新挡掉。提前抛出让上面收尾。
            throw new BizException("WATERMARK_PROCESSING_FAILED",
                "视频水印任务的租约已失效（可能已被重新调度），本次结果作废。");
        }
    }

    /** 旧对象在结果发布成功后才删；删失败只是留个孤儿，绝不影响这一次成功。 */
    private void cleanupOldObject(String oldKey) {
        objects.deleteAfterCommit(oldKey);
    }

    void scheduleVideoProcessing(UUID id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    claimAndEnqueueVideo(id);
                }
            });
        } else {
            claimAndEnqueueVideo(id);
        }
    }

    /**
     * 认领后入队。**必须认领**：不认领的话，排在队列里的行 `claim_token` 仍是 NULL，
     * 定时 reaper 会把"没人认领"当成滞留任务抢走 —— 同一行跑两遍。
     */
    private void claimAndEnqueueVideo(UUID id) {
        UUID claim = UUID.randomUUID();
        if (fileMapper.claimProcessing(id, claim) == 0) return; // 别人已经在跑
        enqueueVideoProcessing(id, claim);
    }

    /**
     * 入队。**队列满不再失败**：行和对象都已经写好了，标 FAILED 等于把用户刚传上来的视频丢掉。
     * 改成把租约还回去、保持 PROCESSING，等前面的任务完成时把它泵进来（见上面那层 try/finally 的
     * `reap(1)`），定时 reaper 兜底。
     *
     * <p>上传路径与恢复路径**语义一致**：原来上传路径是"满了就 FAILED"，于是第 23 个并发视频必然
     * "上传成功却失败"——而它其实只是排队排在后面。
     */
    private void enqueueVideoProcessing(UUID id, UUID claim) {
        try {
            fileProcessingExecutor.execute(() -> {
                try {
                    processVideoWatermark(id, claim);
                } finally {
                    // 完成即泵：刚空出一个槽位，把排在库里的下一条接上。被拒/没得领都会直接返回，不重试。
                    reap(1);
                }
            });
        } catch (RejectedExecutionException exception) {
            // 保持 PROCESSING 并把租约还回去，让"完成即泵"或下一轮 reaper 再捞。
            log.info("转码队列已满，任务留在待处理队列里等空位（file={}）", id);
            fileMapper.releaseProcessingClaim(id, claim);
        }
    }
}
