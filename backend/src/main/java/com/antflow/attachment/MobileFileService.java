package com.antflow.attachment;

import com.antflow.authz.AuthorizationService;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 移动端上传件的对外门面：**只做转发**，实现分别落在
 * {@link MobileFileUploadService}（上传/落库）、{@link MobileFileReader}（元数据/下载）、
 * {@link MobileFileDeletionService}（删除）、{@link MobileVideoWatermarkPipeline}（异步视频水印）。
 *
 * <p>为什么要留着这一层：
 * <ol>
 *   <li>Spring 只注册这一个 bean，定时/事件注解留在这里（搬到非 bean 的协作者上会静默失效）；</li>
 *   <li>{@code @Transactional} 只有在**代理入口**上才生效，删除的事务边界必须留在本类；</li>
 *   <li>Controller、微信侧调用方、单测都按原来的公开签名继续用，重构对它们透明。</li>
 * </ol>
 *
 * <p>协作者都是包内可见的普通对象（由本构造器组装，不注册成 bean）：它们的依赖也全部走构造器，
 * 谁都不反向引用本类，依赖方向是单向的 → 门面 → 协作者。
 */
@Service
@Slf4j
public class MobileFileService {
    private final MobileFileUploadService uploads;
    private final MobileFileReader reader;
    private final MobileFileDeletionService deletions;
    private final MobileVideoWatermarkPipeline videoPipeline;

    /**
     * 显式构造（原来靠 Lombok）：信号量要从配置建，而 Lombok 生成的字段初始化器**早于**构造体执行，
     * 在字段初始化器里读 {@code properties} 会 NPE。签名保持不变，测试与 Spring 都不用改。
     */
    public MobileFileService(MobileFileMapper fileMapper, MobileFileAccessMapper accessMapper,
                             FileStorage storage, MobileFileProperties properties,
                             MediaWatermarkProcessor watermarkProcessor,
                             AuthorizationService authorizationService,
                             Executor fileProcessingExecutor, TransactionTemplate transactions,
                             MediaDurationProbe durationProbe) {
        MobileFileStaging staging = new MobileFileStaging();
        MediaUploadValidator validator = new MediaUploadValidator(properties);
        MobileFileObjectStore objects = new MobileFileObjectStore(storage);
        this.reader = new MobileFileReader(fileMapper, accessMapper, storage, authorizationService);
        this.deletions = new MobileFileDeletionService(fileMapper, accessMapper, objects);
        this.videoPipeline = new MobileVideoWatermarkPipeline(fileMapper, storage, properties,
            watermarkProcessor, fileProcessingExecutor, durationProbe, objects);
        this.uploads = new MobileFileUploadService(fileMapper, properties, watermarkProcessor,
            transactions, durationProbe, validator, staging, objects, videoPipeline);
    }

    public MobileFileDto upload(MultipartFile file, long ownerId) {
        return uploads.upload(file, ownerId);
    }

    public MobileFileDto upload(MultipartFile file, long ownerId, boolean watermark,
                                String watermarkText) {
        return uploads.upload(file, ownerId, watermark, watermarkText);
    }

    public MobileFileDto getMetadata(UUID id, long userId, java.util.Collection<String> roles) {
        return reader.getMetadata(id, userId, roles);
    }

    public MobileFileContent readContent(UUID id, long userId, java.util.Collection<String> roles) {
        return reader.readContent(id, userId, roles);
    }

    /** 事务边界就在这一层：删行与"提交后删对象"的注册必须落在同一个事务里。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id, long userId) {
        deletions.delete(id, userId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingVideoProcessing() {
        videoPipeline.recoverPendingVideoProcessing();
    }

    @Scheduled(fixedDelayString = "${antflow.mobile.files.processing-reap-interval-ms:60000}")
    void reapStaleProcessing() {
        videoPipeline.reapStaleProcessing();
    }

    /** 包内可见：单测直接驱动一次转码，不必真的排队。 */
    void processVideoWatermark(UUID id, UUID claim) {
        videoPipeline.processVideoWatermark(id, claim);
    }
}
