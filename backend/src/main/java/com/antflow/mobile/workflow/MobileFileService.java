package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.engine.BizException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@Slf4j
public class MobileFileService {
    private static final String READY_STATUS = "READY";
    private static final String PROCESSING_STATUS = "PROCESSING";
    private static final String FAILED_STATUS = "FAILED";
    private static final String DELETED_STATUS = "DELETED";
    private static final int JPEG_SIGNATURE_SIZE = 3;
    /** 给客户端的失败原因最多多少字（客户端要保持可读，不搬整段 ffmpeg 报错）。 */
    private static final int USER_REASON_CHARS = 200;

    private final MobileFileMapper fileMapper;
    private final MobileFileAccessMapper accessMapper;
    private final FileStorage storage;
    private final MobileFileProperties properties;
    private final MediaWatermarkProcessor watermarkProcessor;
    private final AuthorizationService authorizationService;
    private final Executor fileProcessingExecutor;
    private final TransactionTemplate transactions;
    /** 同时跑几个图片水印 ffmpeg：每个都要一份磁盘（输入+输出）与一个进程，不限并发会把可写层打满。 */
    private final Semaphore imageWatermarkPermits;

    /**
     * 显式构造（原来靠 Lombok）：信号量要从配置建，而 Lombok 生成的字段初始化器**早于**构造体执行，
     * 在字段初始化器里读 {@code properties} 会 NPE。
     */
    public MobileFileService(MobileFileMapper fileMapper, MobileFileAccessMapper accessMapper,
                             FileStorage storage, MobileFileProperties properties,
                             MediaWatermarkProcessor watermarkProcessor,
                             AuthorizationService authorizationService,
                             Executor fileProcessingExecutor, TransactionTemplate transactions) {
        this.fileMapper = fileMapper;
        this.accessMapper = accessMapper;
        this.storage = storage;
        this.properties = properties;
        this.watermarkProcessor = watermarkProcessor;
        this.authorizationService = authorizationService;
        this.fileProcessingExecutor = fileProcessingExecutor;
        this.transactions = transactions;
        this.imageWatermarkPermits = new Semaphore(
            Math.max(1, properties.getImageWatermarkConcurrency()));
    }

    public MobileFileDto upload(MultipartFile file, long ownerId) {
        return upload(file, ownerId, false, null);
    }

    /**
     * 上传入口。**故意不是 {@code @Transactional}**：`@Transactional` 在方法入口就取一个连接，
     * 而图片水印要等一个只有 N 个许可的信号量——并发一高，连接会被"排队等 ffmpeg"的请求占满，
     * 其余接口全被拖慢（压测里元数据 GET 因此到过 1.97s）。所以 ffmpeg 放在事务外，只有
     * DB+存储那一段走 {@link #persistUpload}（见那里的注释：行锁必须覆盖到 MinIO 的写入）。
     */
    public MobileFileDto upload(MultipartFile file, long ownerId, boolean watermark, String watermarkText) {
        validateBasic(file);
        StagedFile staged = stage(file);
        try {
            String submittedContentType = normalize(file.getContentType());
            // validateBasic 比的是**声明**的大小，能被撒谎的客户端绕过（声明小、实际大）。
            // 真实字节数只有落盘数完才知道，所以落盘之后必须用同一口径再比一次。
            if (staged.size() > sizeLimit(submittedContentType)) {
                throw new BizException("BAD_FILE", "file is too large");
            }
            validateContent(submittedContentType, readHeader(staged.path()));
            String watermarkLabel = watermarkText == null ? "" : watermarkText.trim();
            boolean supportedForWatermark = watermark && watermarkProcessor.supports(submittedContentType);
            // 「要水印但没给文案」以前是静默存原图：调用方以为加了水印，实际什么都没加。明说。
            if (supportedForWatermark && watermarkLabel.isEmpty()) {
                throw new BizException("WATERMARK_TEXT_REQUIRED",
                    "该字段要求加水印，但未提供水印文案（watermarkText）");
            }
            boolean applyWatermark = supportedForWatermark && !watermarkLabel.isEmpty();
            boolean asyncVideo = applyWatermark && submittedContentType.startsWith("video/");

            if (applyWatermark && !asyncVideo) {
                // 全程走磁盘：staged 本来就是临时文件，ffmpeg 直接读它，成品再覆盖回去。
                // 以前是 readStagedBytes + apply(byte[]) —— 整份图片进堆，而且是在抢锁**之前**读的，
                // 等待者各自抱着一份内存谁也不松手。
                Path workDir = null;
                try {
                    workDir = Files.createTempDirectory("antflow-image-watermark-");
                    Path output = applyImageWatermark(staged.path(), workDir, submittedContentType,
                        watermarkLabel);
                    Files.copy(output, staged.path(), StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    MediaWatermarkProcessor.deleteRecursively(workDir);
                }
                submittedContentType = watermarkProcessor.resultContentType(submittedContentType);
                staged = new StagedFile(staged.path(), Files.size(staged.path()),
                    sha256(staged.path()));
                // 成品可能比输入大（重编码：码率/封装变了），输入比过了不代表成品合规。
                if (staged.size() > sizeLimit(submittedContentType)) {
                    throw new BizException("BAD_FILE", "file is too large");
                }
            }

            // lambda 只能捕获有效 final 的局部变量，而 staged / submittedContentType 上面被重新赋值过。
            StagedFile persisted = staged;
            String persistedContentType = submittedContentType;
            String originalName = sanitizeName(file.getOriginalFilename());
            return transactions.execute(status -> persistUpload(ownerId, persisted,
                persistedContentType, asyncVideo, watermarkLabel, originalName));
        } catch (IOException exception) {
            // 暂存/写入本地磁盘的失败：技术细节进日志，别把带路径的英文异常甩给用户。
            log.error("上传暂存失败", exception);
            throw new BizException("BAD_FILE", "文件上传失败（服务端暂存异常），请重试");
        } finally {
            deleteTemp(staged.path());
        }
    }

    /**
     * 上传的事务段：去重（含 {@code FOR UPDATE} 行锁）→ 写对象 → 插行 → 视频登记提交后入队。
     *
     * <p>**去重锁必须和 MinIO 的写入在同一个事务里**：否则并发 {@code delete} 会插进"查到重复行"和
     * "写回去"之间（那正是 d95a746 修的东西）。能逃出这里的异常全是 unchecked（{@code writeStorageObject}
     * 把 IOException 包成 BizException、mapper 抛 DataAccessException、入队吞掉 RejectedExecutionException），
     * 所以 TransactionTemplate 的默认回滚 == 原来 {@code rollbackFor = Exception.class}。
     */
    private MobileFileDto persistUpload(long ownerId, StagedFile staged, String submittedContentType,
                                        boolean asyncVideo, String watermarkLabel,
                                        String originalName) {
        if (!asyncVideo) {
            MobileFile existing = fileMapper.selectReadyDuplicateForUpdate(
                ownerId, staged.sha256());
            if (existing != null) {
                // 对象在就只返回旧行：同一份字节重复上传不必再往 MinIO 写一遍（也因此不必碰
                // 那些已被提交单据引用的对象）。不在才补写——那是现在唯一的自愈机会（没有孤儿清扫器）。
                if (!storage.exists(existing.getStorageKey())) {
                    writeStorageObject(existing.getStorageKey(), staged, submittedContentType);
                }
                return toDto(existing);
            }
        }

        UUID id = UUID.randomUUID();
        String storageKey = kindPrefix(submittedContentType) + id + "-" + originalName;
        writeStorageObject(storageKey, staged, submittedContentType);

        MobileFile row = new MobileFile();
        row.setId(id);
        row.setOwnerId(ownerId);
        row.setOriginalName(originalName);
        row.setStorageKey(storageKey);
        row.setContentType(submittedContentType);
        row.setSizeBytes(staged.size());
        row.setSha256(staged.sha256());
        row.setStatus(asyncVideo ? PROCESSING_STATUS : READY_STATUS);
        row.setWatermarkText(asyncVideo ? watermarkLabel : null);
        fileMapper.insert(row);
        if (asyncVideo) scheduleVideoProcessing(id);
        return toDto(row);
    }

    private void writeStorageObject(String storageKey, StagedFile staged, String contentType) {
        try (InputStream content = Files.newInputStream(staged.path())) {
            storage.put(storageKey, content, staged.size(), contentType);
        } catch (IOException exception) {
            log.error("写入对象存储失败：{}", storageKey, exception);
            throw new BizException("FILE_STORAGE_FAILED", "文件存储失败，请稍后重试");
        }
    }

    /**
     * 恢复滞留的异步任务。启动时跑一次，之后由 {@link #reapStaleProcessing()} 定时接管——
     * 以前只有启动这一次：进程里 OOM/被杀留下的 PROCESSING 行除了重启没人管。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingVideoProcessing() {
        reapStaleProcessing();
    }

    /**
     * 定期把"没人认领或租约已过期"的 PROCESSING 行重新排进队列。**有界**（每轮最多
     * {@code processing-reap-limit} 条）且**先认领**：认领不到的说明别人正在跑，直接跳过。
     *
     * <p>被队列拒绝时保持 PROCESSING（{@link #enqueueVideoProcessing}）——原来在启动恢复里被拒就直接标
     * FAILED，一次崩溃留下的积压会有一大半"永久失败"。
     */
    @Scheduled(fixedDelayString = "${antflow.mobile.files.processing-reap-interval-ms:60000}")
    void reapStaleProcessing() {
        int requeued = reap(properties.getProcessingReapLimit());
        if (requeued > 0) {
            log.info("重新排入 {} 个滞留的视频水印任务", requeued);
        }
    }

    /**
     * 认领并排队至多 {@code limit} 条待处理行，返回排进去的条数。
     *
     * <p>三个调用点：启动恢复（{@link #recoverPendingVideoProcessing}）、定时 reaper，以及
     * **每个转码任务完成时**（`reap(1)`，见 {@link #enqueueVideoProcessing} 里包的那层 try/finally）。
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
                sha256(output), toMp4Name(file.getOriginalName()));
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
                truncate(failure.getClass().getSimpleName()
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
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteObjectQuietly(oldKey);
                }
            });
        } else {
            deleteObjectQuietly(oldKey);
        }
    }

    private void deleteObjectQuietly(String key) {
        try {
            storage.delete(key);
        } catch (Exception exception) {
            log.warn("清理旧对象失败（不影响本次结果）：{}", key, exception);
        }
    }

    /**
     * 图片水印：同一时刻最多 `antflow.mobile.files.image-watermark-concurrency`（默认 2）个 ffmpeg 在跑。
     *
     * <p>缓存/磁盘/CPU 都按并发算账（内存问题已经由流式化解决）。等不到许可的请求会排队——
     * 这也是这个同步链路的天然背压；排队时**不占数据库连接**（见 {@link #upload}）。
     */
    private Path applyImageWatermark(Path input, Path workDir, String contentType, String label) {
        try {
            imageWatermarkPermits.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException("WATERMARK_PROCESSING_FAILED", "水印处理被中断，请重试。");
        }
        try {
            return watermarkProcessor.applyTo(input, workDir, contentType, label);
        } finally {
            imageWatermarkPermits.release();
        }
    }

    private void scheduleVideoProcessing(UUID id) {
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

    public MobileFileDto getMetadata(UUID id, long userId, java.util.Collection<String> roles) {
        return toDto(requireReadable(id, userId, roles));
    }

    public MobileFileContent readContent(UUID id, long userId, java.util.Collection<String> roles) {
        MobileFile file = requireReadable(id, userId, roles);
        if (!READY_STATUS.equals(file.getStatus())) {
            throw new BizException("FILE_PROCESSING", "file is not ready");
        }
        return new MobileFileContent(toDto(file), storage.get(file.getStorageKey()));
    }

    /**
     * 删除还没提交的附件。
     *
     * <p>三件事按顺序都重要：
     * <ol>
     *   <li>**先加行锁**（{@code FOR UPDATE}）：否则"检查有没有被提交"与提交方"检查文件是否 READY"
     *       会交错——提交成功了，附件却指向一个已删文件、对象也没了（永久坏引用）；
     *   <li>**处理中的视频不给删**：后台正往同一个 key 写结果，删了会留下读不出来的孤儿对象；
     *   <li>**对象在事务提交后删**：提交前删的话，事务一旦回滚就变成"READY 行 + 对象已丢"，
     *       而反过来（提交成功、删除失败）最多是个孤儿对象，没人会读到它。
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id, long userId) {
        List<MobileFile> locked = fileMapper.selectByIdsForUpdate(List.of(id));
        MobileFile file = locked.isEmpty() ? null : locked.get(0);
        if (file == null || DELETED_STATUS.equals(file.getStatus()) || file.getDeletedAt() != null) {
            throw new BizException("FILE_NOT_FOUND", "file not found");
        }
        if (!Objects.equals(file.getOwnerId(), userId)) {
            throw new AccessDeniedException("file belongs to another user");
        }
        if (PROCESSING_STATUS.equals(file.getStatus())) {
            throw new BizException("BAD_FILE_STATE", "视频正在加水印，处理完再删");
        }
        if (accessMapper.countLinks(id) > 0) {
            throw new BizException("BAD_FILE_STATE", "file already submitted");
        }
        file.setStatus(DELETED_STATUS);
        file.setDeletedAt(OffsetDateTime.now());
        fileMapper.updateById(file);
        deleteObjectAfterCommit(file.getStorageKey());
    }

    /** 事务提交后再删对象；删失败只记日志（行已经 DELETED，最坏是个没人引用的孤儿对象）。 */
    private void deleteObjectAfterCommit(String storageKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteObjectQuietly(storageKey);
                }
            });
        } else {
            deleteObjectQuietly(storageKey);
        }
    }

    private void validateBasic(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BizException("BAD_FILE", "file is empty");
        }
        if (file.getSize() > sizeLimit(normalize(file.getContentType()))) {
            throw new BizException("BAD_FILE", "file is too large");
        }
    }

    private long sizeLimit(String contentType) {
        return contentType.startsWith("video/")
            ? properties.getMaxVideoBytes() : properties.getMaxBytes();
    }

    private StagedFile stage(MultipartFile file) {
        Path path = null;
        try {
            path = Files.createTempFile("antflow-upload-", ".bin");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream input = new DigestInputStream(file.getInputStream(), digest);
                 var output = Files.newOutputStream(path)) {
                size = input.transferTo(output);
            }
            return new StagedFile(path, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteTemp(path);
            log.error("暂存上传文件失败", exception);
            throw new BizException("BAD_FILE", "文件上传失败（服务端暂存异常），请重试");
        }
    }

    /**
     * 读文件头给魔数嗅探用。512 字节是给"带 XML 声明的 SVG"留的余量——`<?xml ...?>` 之后还要能
     * 看见 `<svg` 根（只读 16 字节时只能看见声明本身，就没法把任意 XML 和 SVG 区分开）。
     */
    private static byte[] readHeader(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(512);
        }
    }

    private void validateContent(String submittedContentType, byte[] content) {
        // Attachments accept arbitrary formats (including executables such as
        // .dll/.exe), so no signature-based rejection is applied here.
        if (submittedContentType.startsWith("image/")) {
            validateImageContent(submittedContentType, content);
        } else if (submittedContentType.startsWith("video/")) {
            String detectedContentType = detectContentType(content);
            // mp4 / quicktime / 3gpp / webm are all accepted regardless of the
            // exact submitted subtype, so iPhone .mov/.mp4 variances pass.
            if (detectedContentType == null || !detectedContentType.startsWith("video/")) {
                throw new BizException("BAD_FILE", "unsupported file content");
            }
        }
        // Other content types (attachments) are accepted as-is.
    }

    /**
     * 图片只做一件事：确认它**真的是图片**。
     *
     * <p>不校验"声明的子类型与字节一致"——安卓/微信的图库经常把 jpeg 标成 png，那种一律放行；
     * 但声明的 `image/*` 后面如果是任意字节（以前就是这么放过去的），它会一路走进同步的 ffmpeg 水印
     * 链路，最后甩给用户一个看不懂的 ffmpeg 报错。
     */
    private void validateImageContent(String submittedContentType, byte[] content) {
        if (detectImageContentType(content) == null) {
            throw new BizException("BAD_FILE",
                "文件内容不是支持的图片格式（jpeg/png/gif/webp/bmp/tiff/heic/avif/svg）");
        }
    }

    private static String detectImageContentType(byte[] content) {
        if (startsWith(content, new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        })) {
            return "image/png";
        }
        if (content.length >= JPEG_SIGNATURE_SIZE
            && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8
            && (content[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (startsWith(content, ascii("GIF87a")) || startsWith(content, ascii("GIF89a"))) {
            return "image/gif";
        }
        // RIFF....WEBP
        if (startsWith(content, ascii("RIFF")) && content.length >= 12
            && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return "image/webp";
        }
        if (startsWith(content, ascii("BM"))) {
            return "image/bmp";
        }
        // TIFF：II*\0（小端）或 MM\0*（大端）
        if (content.length >= 4
            && ((content[0] == 'I' && content[1] == 'I' && content[2] == 0x2A && content[3] == 0x00)
            || (content[0] == 'M' && content[1] == 'M' && content[2] == 0x00 && content[3] == 0x2A))) {
            return "image/tiff";
        }
        // ISO-BMFF：``????ftyp<brand>``。**iPhone 的 HEIC 必须认**：`convertHeic` 没开的字段（默认没开）
        // 是原样上传的，漏了它选张照片就被判成"不是图片"。AVIF 同理。
        if (content.length >= 12 && content[4] == 'f' && content[5] == 't' && content[6] == 'y'
            && content[7] == 'p') {
            String brand = new String(content, 8, 4, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
            if (brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("msf")) {
                return "image/heic";
            }
            if (brand.startsWith("avif") || brand.startsWith("avis")) {
                return "image/avif";
            }
        }
        // SVG 是文本（`<svg` 或 `<?xml ...?><svg`），可能带 BOM 或前导空白。
        String head = new String(content, 0, Math.min(content.length, 64), StandardCharsets.UTF_8)
            .replace("\uFEFF", "");
        while (head.startsWith(" ") || head.startsWith("\n") || head.startsWith("\r")
            || head.startsWith("\t")) {
            head = head.substring(1);
        }
        head = head.toLowerCase(Locale.ROOT);
        if (head.startsWith("<svg") || head.startsWith("<!doctype svg")) {
            return "image/svg+xml";
        }
        // 带 XML 声明的 SVG：**声明之后必须真的出现 `<svg` 根**。只看 `<?xml` 会把任意 XML 文档
        // （RSS、配置、随便一段 XML）都当成图片放进来——报错文案也就跟着指错方向。
        if (head.startsWith("<?xml")) {
            int prologEnd = head.indexOf("?>");
            int root = head.indexOf("<svg");
            if (prologEnd > 0 && root > prologEnd) {
                return "image/svg+xml";
            }
        }
        return null;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }


    private static String detectContentType(byte[] content) {
        if (startsWith(content, new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        })) {
            return "image/png";
        }
        if (content.length >= JPEG_SIGNATURE_SIZE
            && (content[0] & 0xFF) == 0xFF
            && (content[1] & 0xFF) == 0xD8
            && (content[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (startsWith(content, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if (content.length >= 12
            && matchesAt(content, "ftyp".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 4)) {
            String brand = new String(content, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)
                .toLowerCase(Locale.ROOT);
            if (brand.startsWith("qt")) {
                return "video/quicktime";
            }
            if (brand.startsWith("3gp")) {
                return "video/3gpp";
            }
            // 图片品牌也是 ftyp 盒子（HEIC/AVIF 家族）：声明成 video/* 的图片不能被当成视频送进
            // 水印链路（那会白跑一次 ffmpeg 然后把行标成失败）。这些品牌一律"不是视频"。
            if (brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("msf")
                || brand.startsWith("avi")) {
                return null;
            }
            return "video/mp4";
        }
        if (startsWith(content, new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3})) {
            return "video/webm";
        }
        return null;
    }




    private static boolean matchesAt(byte[] content, byte[] marker, int start) {
        if (start < 0 || start + marker.length > content.length) {
            return false;
        }
        for (int index = 0; index < marker.length; index++) {
            if (content[start + index] != marker[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    /** 流式算哈希：结果文件可能有 100MB，别为了算摘要再读进堆一份。 */
    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("could not hash " + path, exception);
        }
    }

    private static String normalize(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }


    private static String kindPrefix(String contentType) {
        if (contentType.startsWith("image/")) {
            return "image/";
        }
        if (contentType.startsWith("video/")) {
            return "video/";
        }
        return "file/";
    }

    private static String toMp4Name(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(0, dot) + ".mp4";
        }
        return name + ".mp4";
    }

    private static String sanitizeName(String originalName) {
        String name = originalName == null || originalName.isBlank() ? "file" : originalName;
        name = name.replace("\\", "/");
        int separator = name.lastIndexOf('/');
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "file" : name;
    }

    private static void deleteTemp(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private MobileFile requireReadable(UUID id, long userId, java.util.Collection<String> roles) {
        MobileFile file = requireExisting(id);
        boolean admin = roles != null && roles.contains("admin");
        boolean owner = Objects.equals(file.getOwnerId(), userId);
        // admin/owner 直接放行：以前这里**无条件**去查关联实例再逐实例判权，于是打开一页 20 张图
        // 就是几十次多余查询（而且每次渲染都重来）。
        if (admin || owner) return file;
        boolean linkedInstanceReadable = accessMapper.selectLinkedInstanceIds(id).stream()
            .anyMatch(instanceId -> authorizationService.canReadFullInstance(instanceId, userId));
        if (linkedInstanceReadable) return file;
        throw new HiddenResourceException("file not found");
    }

    private MobileFile requireExisting(UUID id) {
        MobileFile file = fileMapper.selectById(id);
        if (file == null || DELETED_STATUS.equals(file.getStatus()) || file.getDeletedAt() != null) {
            throw new BizException("FILE_NOT_FOUND", "file not found");
        }
        return file;
    }

    private static MobileFileDto toDto(MobileFile file) {
        return new MobileFileDto(
            file.getId(),
            file.getOriginalName(),
            file.getContentType(),
            file.getSizeBytes(),
            "/api/mobile/files/" + file.getId() + "/content",
            file.getStatus(),
            // 失败原因只在 FAILED 时给（已在写入时就脱敏过），客户端轮询到 FAILED 才有的可展示。
            FAILED_STATUS.equals(file.getStatus())
                ? truncate(file.getProcessingError(), USER_REASON_CHARS) : null
        );
    }

    private record StagedFile(Path path, long size, String sha256) {
    }
}
