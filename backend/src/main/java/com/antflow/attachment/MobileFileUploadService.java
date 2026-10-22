package com.antflow.attachment;

import com.antflow.engine.BizException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传入口的编排：校验 → 暂存 → 同步图片水印 → 事务段（去重 / 插行 / 写对象 / 提交后入队）。
 *
 * <p>从 {@code MobileFileService} 原样搬出（方法体逐字未改）。两条**不能动**的时序：
 * <ol>
 *   <li>ffmpeg 在事务**外**跑（见 {@link #upload} 的注释）；</li>
 *   <li>事务段里**先插行、后写对象**（见 {@link #persistUpload} 的注释）。</li>
 * </ol>
 */
@Slf4j
class MobileFileUploadService {
    private static final String READY_STATUS = "READY";
    private static final String PROCESSING_STATUS = "PROCESSING";

    private final MobileFileMapper fileMapper;
    private final MediaWatermarkProcessor watermarkProcessor;
    private final TransactionTemplate transactions;
    private final MediaDurationProbe durationProbe;
    private final MediaUploadValidator validator;
    private final MobileFileStaging staging;
    private final MobileFileObjectStore objects;
    private final MobileVideoWatermarkPipeline pipeline;
    /**
     * 同时跑几个图片水印 ffmpeg：每个都要一份磁盘（输入+输出）与一个进程，不限并发会把可写层打满。
     * 由构造器从配置建（字段初始化器早于构造体执行，在那里读 properties 会 NPE）。
     */
    private final Semaphore imageWatermarkPermits;

    MobileFileUploadService(MobileFileMapper fileMapper, MobileFileProperties properties,
                            MediaWatermarkProcessor watermarkProcessor,
                            TransactionTemplate transactions, MediaDurationProbe durationProbe,
                            MediaUploadValidator validator, MobileFileStaging staging,
                            MobileFileObjectStore objects, MobileVideoWatermarkPipeline pipeline) {
        this.fileMapper = fileMapper;
        this.watermarkProcessor = watermarkProcessor;
        this.transactions = transactions;
        this.durationProbe = durationProbe;
        this.validator = validator;
        this.staging = staging;
        this.objects = objects;
        this.pipeline = pipeline;
        this.imageWatermarkPermits = new Semaphore(
            Math.max(1, properties.getImageWatermarkConcurrency()));
    }

    MobileFileDto upload(MultipartFile file, long ownerId) {
        return upload(file, ownerId, false, null);
    }

    /**
     * 上传入口。**故意不是 {@code @Transactional}**：`@Transactional` 在方法入口就取一个连接，
     * 而图片水印要等一个只有 N 个许可的信号量——并发一高，连接会被"排队等 ffmpeg"的请求占满，
     * 其余接口全被拖慢（压测里元数据 GET 因此到过 1.97s）。所以 ffmpeg 放在事务外，只有
     * DB+存储那一段走 {@link #persistUpload}（见那里的注释：行锁必须覆盖到 MinIO 的写入）。
     */
    MobileFileDto upload(MultipartFile file, long ownerId, boolean watermark, String watermarkText) {
        validator.validateBasic(file);
        MobileFileStaging.StagedFile staged = staging.stage(file);
        try {
            String submittedContentType = MediaUploadValidator.normalize(file.getContentType());
            // validateBasic 比的是**声明**的大小，能被撒谎的客户端绕过（声明小、实际大）。
            // 真实字节数只有落盘数完才知道，所以落盘之后必须用同一口径再比一次。
            if (staged.size() > validator.sizeLimit(submittedContentType)) {
                throw new BizException("BAD_FILE", "file is too large");
            }
            validator.validateContent(submittedContentType, MobileFileStaging.readHeader(staged.path()));
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
                staged = new MobileFileStaging.StagedFile(staged.path(), Files.size(staged.path()),
                    MobileFileStaging.sha256(staged.path()));
                // 成品可能比输入大（重编码：码率/封装变了），输入比过了不代表成品合规。
                if (staged.size() > validator.sizeLimit(submittedContentType)) {
                    throw new BizException("BAD_FILE", "file is too large");
                }
            }

            // lambda 只能捕获有效 final 的局部变量，而 staged / submittedContentType 上面被重新赋值过。
            MobileFileStaging.StagedFile persisted = staged;
            String persistedContentType = submittedContentType;
            String originalName = MobileFileStaging.sanitizeName(file.getOriginalFilename());
            Double duration = submittedContentType.startsWith("audio/")
                || submittedContentType.startsWith("video/") ? durationProbe.seconds(staged.path()) : null;
            return transactions.execute(status -> persistUpload(ownerId, persisted,
                persistedContentType, asyncVideo, watermarkLabel, originalName, duration));
        } catch (IOException exception) {
            // 暂存/写入本地磁盘的失败：技术细节进日志，别把带路径的英文异常甩给用户。
            log.error("上传暂存失败", exception);
            throw new BizException("BAD_FILE", "文件上传失败（服务端暂存异常），请重试");
        } finally {
            MobileFileStaging.deleteTemp(staged.path());
        }
    }

    /**
     * 上传的事务段：去重（含 {@code FOR UPDATE} 行锁）→ 插行 → 写对象 → 视频登记提交后入队。
     *
     * <p>**先插行、后写对象**（key 由这里生成的 UUID 算得出，两者没有先后依赖）：反过来的话，
     * `put` 成功而事务最终没提交（连接断、进程被杀）就留下一个没人引用的对象。现在最坏只是
     * "行在、对象没写成"，而那种情况整个事务回滚，行也不会留下。
     *
     * <p>**去重锁必须和 MinIO 的写入在同一个事务里**：否则并发 {@code delete} 会插进"查到重复行"和
     * "写回去"之间（那正是 d95a746 修的东西）。能逃出这里的异常全是 unchecked（{@code writeStorageObject}
     * 把 IOException 包成 BizException、mapper 抛 DataAccessException、入队吞掉 RejectedExecutionException），
     * 所以 TransactionTemplate 的默认回滚 == 原来 {@code rollbackFor = Exception.class}。
     */
    private MobileFileDto persistUpload(long ownerId, MobileFileStaging.StagedFile staged,
                                        String submittedContentType,
                                        boolean asyncVideo, String watermarkLabel,
                                        String originalName, Double duration) {
        if (!asyncVideo) {
            MobileFile existing = fileMapper.selectReadyDuplicateForUpdate(
                ownerId, staged.sha256());
            if (existing != null) {
                // 对象在就只返回旧行：同一份字节重复上传不必再往 MinIO 写一遍（也因此不必碰
                // 那些已被提交单据引用的对象）。不在才补写——读的时候对不上就是这样自愈的。
                if (!objects.exists(existing.getStorageKey())) {
                    objects.writeStorageObject(existing.getStorageKey(), staged, submittedContentType);
                }
                if (existing.getDurationSeconds() == null && duration != null) {
                    existing.setDurationSeconds(duration);
                    fileMapper.updateById(existing);
                }
                return MobileFileReader.toDto(existing);
            }
        }

        UUID id = UUID.randomUUID();
        String storageKey = MobileFileStaging.kindPrefix(submittedContentType) + id + "-" + originalName;

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
        row.setDurationSeconds(duration);
        fileMapper.insert(row);
        objects.writeStorageObject(storageKey, staged, submittedContentType);
        if (asyncVideo) pipeline.scheduleVideoProcessing(id);
        return MobileFileReader.toDto(row);
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
}
