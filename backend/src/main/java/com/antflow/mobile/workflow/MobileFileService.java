package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
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
    /** 同时跑几个图片水印 ffmpeg：每个都要一份磁盘（输入+输出）与一个进程，不限并发会把可写层打满。 */
    private final java.util.concurrent.Semaphore imageWatermarkPermits = new java.util.concurrent.Semaphore(2);

    @Transactional(rollbackFor = Exception.class)
    public MobileFileDto upload(MultipartFile file, long ownerId) {
        return upload(file, ownerId, false, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public MobileFileDto upload(MultipartFile file, long ownerId, boolean watermark, String watermarkText) {
        validateBasic(file);
        StagedFile staged = stage(file);
        try {
            String submittedContentType = normalize(file.getContentType());
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
            }

            if (!asyncVideo) {
                MobileFile existing = findReadyDuplicate(ownerId, staged.sha256());
                if (existing != null) {
                    writeStorageObject(existing.getStorageKey(), staged, submittedContentType);
                    return toDto(existing);
                }
            }

            UUID id = UUID.randomUUID();
            String originalName = sanitizeName(file.getOriginalFilename());
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
        } catch (IOException exception) {
            // 暂存/写入本地磁盘的失败：技术细节进日志，别把带路径的英文异常甩给用户。
            log.error("上传暂存失败", exception);
            throw new BizException("BAD_FILE", "文件上传失败（服务端暂存异常），请重试");
        } finally {
            deleteTemp(staged.path());
        }
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
     * <p>被队列拒绝时保持 PROCESSING（见 {@link RejectPolicy#LEAVE}）——原来在启动恢复里被拒就直接标
     * FAILED，一次崩溃留下的积压会有一大半"永久失败"。
     */
    @Scheduled(fixedDelayString = "${antflow.mobile.files.processing-reap-interval-ms:60000}")
    void reapStaleProcessing() {
        OffsetDateTime staleBefore = OffsetDateTime.now()
            .minusMinutes(properties.getProcessingStaleMinutes());
        // 这一轮的租约 token：一批行共用一个（它们本来就属于同一轮调度），拒绝时按它整体归还。
        UUID reapClaim = UUID.randomUUID();
        java.util.List<UUID> claimed = fileMapper.claimStaleProcessing(staleBefore,
            properties.getProcessingReapLimit(), reapClaim);
        if (claimed.isEmpty()) return;
        log.info("重新排入 {} 个滞留的视频水印任务", claimed.size());
        // claimStaleProcessing 已经把同一批的租约写成一个 token 了，这里只负责入队；
        // 被队列拒绝就 release 这个 token 等下一轮（不会误伤别人的租约）。
        claimed.forEach(id -> enqueueVideoProcessing(id, reapClaim, RejectPolicy.LEAVE));
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
     * 图片水印：同一时刻最多 {@value #IMAGE_WATERMARK_PERMITS} 个 ffmpeg 在跑。
     *
     * <p>缓存/磁盘/CPU 都按并发算账（内存问题已经由流式化解决）。等不到许可的请求会排队——
     * 这也是这个同步链路的天然背压。
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

    /** 队列满时怎么办。**两处语义刻意不同**，所以让调用点明说，而不是一个布尔。 */
    private enum RejectPolicy {
        /** 刚上传完：客户端正在轮询，必须马上给个答案。 */
        FAIL,
        /** 恢复/reaper：没空位就保持 PROCESSING，下一轮再来（标 FAILED 就永久丢了）。 */
        LEAVE
    }

    private void scheduleVideoProcessing(UUID id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    claimAndEnqueueVideo(id, RejectPolicy.FAIL);
                }
            });
        } else {
            claimAndEnqueueVideo(id, RejectPolicy.FAIL);
        }
    }

    /**
     * 认领后入队。**必须认领**：不认领的话，排在队列里的行 `claim_token` 仍是 NULL，
     * 定时 reaper 会把"没人认领"当成滞留任务抢走 —— 同一行跑两遍。
     */
    private void claimAndEnqueueVideo(UUID id, RejectPolicy policy) {
        UUID claim = UUID.randomUUID();
        if (fileMapper.claimProcessing(id, claim) == 0) return; // 别人已经在跑
        enqueueVideoProcessing(id, claim, policy);
    }

    private void enqueueVideoProcessing(UUID id, UUID claim, RejectPolicy policy) {
        try {
            fileProcessingExecutor.execute(() -> processVideoWatermark(id, claim));
        } catch (RejectedExecutionException exception) {
            if (policy == RejectPolicy.FAIL) {
                fileMapper.failProcessing(id, claim, "file processing queue is full");
            } else {
                // 保持 PROCESSING 并把租约还回去，让下一轮 reaper 再捞。
                fileMapper.releaseProcessingClaim(id, claim);
            }
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

    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id, long userId) {
        MobileFile file = requireExisting(id);
        if (!Objects.equals(file.getOwnerId(), userId)) {
            throw new AccessDeniedException("file belongs to another user");
        }
        if (accessMapper.countLinks(id) > 0) {
            throw new BizException("BAD_FILE_STATE", "file already submitted");
        }
        file.setStatus(DELETED_STATUS);
        file.setDeletedAt(OffsetDateTime.now());
        fileMapper.updateById(file);
        try {
            storage.delete(file.getStorageKey());
        } catch (IOException exception) {
            log.error("删除对象存储失败：{}", file.getStorageKey(), exception);
            throw new BizException("FILE_STORAGE_FAILED", "文件删除失败，请稍后重试");
        }
    }

    private void validateBasic(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BizException("BAD_FILE", "file is empty");
        }
        String contentType = normalize(file.getContentType());
        long limit = contentType.startsWith("video/") ? properties.getMaxVideoBytes() : properties.getMaxBytes();
        if (file.getSize() > limit) {
            throw new BizException("BAD_FILE", "file is too large");
        }
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

    private static byte[] readHeader(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(16);
        }
    }

    private MobileFile findReadyDuplicate(long ownerId, String sha256) {
        return fileMapper.selectOne(new QueryWrapper<MobileFile>()
            .eq("owner_id", ownerId)
            .eq("sha256", sha256)
            .eq("status", READY_STATUS)
            .isNull("deleted_at"));
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
        // SVG 是文本（`<svg` / `<?xml`），可能带 BOM 或前导空白。
        String head = new String(content, 0, Math.min(content.length, 64), StandardCharsets.UTF_8)
            .replace("\uFEFF", "");
        while (head.startsWith(" ") || head.startsWith("\n") || head.startsWith("\r")
            || head.startsWith("\t")) {
            head = head.substring(1);
        }
        head = head.toLowerCase(Locale.ROOT);
        if (head.startsWith("<svg") || head.startsWith("<?xml") || head.startsWith("<!doctype svg")) {
            return "image/svg+xml";
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
        boolean linkedInstanceReadable = accessMapper.selectLinkedInstanceIds(id).stream()
            .anyMatch(instanceId -> authorizationService.canReadFullInstance(instanceId, userId));
        if (admin || owner || linkedInstanceReadable) {
            return file;
        }
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
