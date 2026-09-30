package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.engine.BizException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

class MobileFileServiceTest {
    private MobileFileMapper fileMapper;
    private MobileFileAccessMapper accessMapper;
    private CapturingStorage storage;
    private MediaWatermarkProcessor processor;
    private MobileFileService service;
    private AuthorizationService authorizationService;
    private List<Runnable> backgroundTasks;

    @BeforeEach
    void setUp() {
        fileMapper = Mockito.mock(MobileFileMapper.class);
        accessMapper = Mockito.mock(MobileFileAccessMapper.class);
        storage = new CapturingStorage();
        processor = Mockito.mock(MediaWatermarkProcessor.class);
        authorizationService = Mockito.mock(AuthorizationService.class);
        backgroundTasks = new ArrayList<>();
        MobileFileProperties properties = new MobileFileProperties();
        properties.setMaxBytes(10L * 1024 * 1024);
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, backgroundTasks::add);
    }

    @Test
    void uploadRejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.upload(file, 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("file is empty");
    }

    @Test
    void uploadRejectsOversizedFileBeforeStorageWrite() {
        MobileFileProperties properties = new MobileFileProperties();
        properties.setMaxBytes(4L);
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, backgroundTasks::add);
        MockMultipartFile file = pngFile("large.png", new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D
        });

        assertThatThrownBy(() -> service.upload(file, 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("file is too large");
        assertThat(storage.putCount).isZero();
    }

    @Test
    void uploadAcceptsExecutableAsAttachment() throws Exception {
        // .dll/.exe attachments are allowed; only the MZ header is required.
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);

        byte[] content = new byte[] {0x4D, 0x5A, 0x00, 0x00};
        MobileFileDto dto = service.upload(
            new MockMultipartFile("file", "aida_bench64.dll", "application/x-msdownload", content), 7L);

        assertThat(dto.contentType()).isEqualTo("application/x-msdownload");
        assertThat(dto.name()).isEqualTo("aida_bench64.dll");
        assertThat(storage.contentBytes).isEqualTo(content);
        assertThat(storage.storageKey).startsWith("file/");
    }

    @Test
    void uploadAcceptsArbitraryFileFormat() throws Exception {
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);

        byte[] content = pdfBytes();
        MobileFileDto dto = service.upload(
            new MockMultipartFile("file", "contract.pdf", "application/pdf", content), 7L);

        assertThat(dto.contentType()).isEqualTo("application/pdf");
        assertThat(storage.contentBytes).isEqualTo(content);
        assertThat(storage.storageKey).startsWith("file/");
    }

    @Test
    void uploadAcceptsArbitraryAttachmentFormat() throws Exception {
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);

        byte[] content = "plain text attachment".getBytes(StandardCharsets.UTF_8);
        MobileFileDto dto = service.upload(
            new MockMultipartFile("file", "note.txt", "text/plain", content), 7L);

        assertThat(dto.contentType()).isEqualTo("text/plain");
        assertThat(dto.name()).isEqualTo("note.txt");
        assertThat(storage.contentBytes).isEqualTo(content);
        assertThat(storage.storageKey).startsWith("file/");
    }

    @Test
    void uploadAcceptsImageWithMismatchedMimeLabel() throws Exception {
        // Android file pickers often label JPEG bytes as image/png.
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);

        MobileFileDto dto = service.upload(
            new MockMultipartFile("file", "photo.png", "image/png", jpegBytes()), 7L);

        assertThat(dto.contentType()).isEqualTo("image/png");
        assertThat(storage.contentBytes).isNotEmpty();
        assertThat(storage.storageKey).startsWith("image/");
    }

    @Test
    void uploadRejectsOversizedVideo() {
        MobileFileProperties properties = new MobileFileProperties();
        properties.setMaxBytes(10L * 1024 * 1024);
        properties.setMaxVideoBytes(8L);
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, backgroundTasks::add);

        MockMultipartFile file = new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[9]);

        assertThatThrownBy(() -> service.upload(file, 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("file is too large");
        assertThat(storage.putCount).isZero();
    }

    @Test
    void uploadAcceptsMp4Video() throws Exception {
        MobileFileProperties properties = new MobileFileProperties();
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, backgroundTasks::add);
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);

        byte[] content = mp4Bytes();
        MobileFileDto dto = service.upload(
            new MockMultipartFile("file", "clip.mp4", "video/mp4", content), 7L);

        assertThat(dto.contentType()).isEqualTo("video/mp4");
        assertThat(dto.name()).isEqualTo("clip.mp4");
        assertThat(storage.contentBytes).isEqualTo(content);
        assertThat(storage.contentType).isEqualTo("video/mp4");
        assertThat(storage.storageKey).startsWith("video/");
    }

    @Test
    void uploadAppliesWatermarkForVideoAndRenamesToMp4() throws Exception {
        MobileFileProperties properties = new MobileFileProperties();
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, backgroundTasks::add);
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);
        Mockito.when(processor.supports("video/quicktime")).thenReturn(true);
        Mockito.when(processor.applyTo(Mockito.any(), Mockito.any(), Mockito.eq("video/quicktime"),
                Mockito.eq("AntFlow")))
            .thenReturn(writeProcessed(new byte[] {1, 2, 3}));
        Mockito.when(processor.resultContentType("video/quicktime")).thenReturn("video/mp4");
        // 入队前必须认领；租约在 publish 时也要能对上（下面 publishProcessed 的 token 断言）。
        Mockito.when(fileMapper.claimProcessing(any(), any())).thenReturn(1);
        Mockito.when(fileMapper.renewProcessingClaim(any(), any())).thenReturn(1);
        Mockito.when(fileMapper.publishProcessed(any(), any(), any(), any(), Mockito.anyLong(),
            any(), any())).thenReturn(1);

        MobileFileDto dto = service.upload(
            new MockMultipartFile("file", "clip.mov", "video/quicktime", movBytes()), 7L, true, "AntFlow");

        assertThat(dto.status()).isEqualTo("PROCESSING");
        assertThat(dto.contentType()).isEqualTo("video/quicktime");
        ArgumentCaptor<MobileFile> captor = ArgumentCaptor.forClass(MobileFile.class);
        Mockito.verify(fileMapper).insert(captor.capture());
        MobileFile row = captor.getValue();
        Mockito.when(fileMapper.selectById(row.getId())).thenReturn(row);

        backgroundTasks.get(0).run();

        // 结果不再覆盖原 key：写到独立 key，再用带 token 的条件更新把行的指针指过去。
        Mockito.verify(fileMapper).publishProcessed(Mockito.eq(row.getId()), any(),
            Mockito.startsWith(row.getStorageKey() + ".wm-"), Mockito.eq("video/mp4"),
            Mockito.eq(3L), any(), Mockito.eq("clip.mp4"));
        // 旧对象在发布成功后清理（测试里没有事务同步，直接删）。
        assertThat(storage.deletedKeys).contains(row.getStorageKey());
        assertThat(storage.contentBytes).isEqualTo(new byte[] {1, 2, 3});
        assertThat(storage.contentType).isEqualTo("video/mp4");
    }

    /** 租约被别人抢走（publish 条件更新影响 0 行）→ 自己的成品丢掉，绝不碰这一行。 */
    @Test
    void publishLosingTheLeaseDropsTheAttemptObject() throws Exception {
        MobileFileProperties properties = new MobileFileProperties();
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, backgroundTasks::add);
        Mockito.when(processor.supports("video/quicktime")).thenReturn(true);
        Mockito.when(processor.applyTo(Mockito.any(), Mockito.any(), any(), any()))
            .thenReturn(writeProcessed(new byte[] {9}));
        Mockito.when(processor.resultContentType("video/quicktime")).thenReturn("video/mp4");
        Mockito.when(fileMapper.renewProcessingClaim(any(), any())).thenReturn(1);
        Mockito.when(fileMapper.publishProcessed(any(), any(), any(), any(), Mockito.anyLong(),
            any(), any())).thenReturn(0);
        MobileFile row = existingFile(UUID.randomUUID(), 7L);
        row.setStatus("PROCESSING");
        Mockito.when(fileMapper.selectById(row.getId())).thenReturn(row);

        service.processVideoWatermark(row.getId(), UUID.randomUUID());

        // 自己的那份 attempt 对象被删掉；行没有被 publish 改过（条件更新 0 行 = 不是我的行）。
        assertThat(storage.deletedKeys).anyMatch(key -> key.startsWith(row.getStorageKey() + ".wm-"));
        Mockito.verify(fileMapper, Mockito.never()).failProcessing(any(), any(), any());
    }

    /** 续租失败（租约已被别人拿走）→ 立刻收手，不浪费时间跑 ffmpeg。 */
    @Test
    void processingAbortsEarlyWhenTheLeaseWasLost() throws Exception {
        MobileFileProperties properties = new MobileFileProperties();
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, backgroundTasks::add);
        Mockito.when(fileMapper.renewProcessingClaim(any(), any())).thenReturn(0);
        MobileFile row = existingFile(UUID.randomUUID(), 7L);
        row.setStatus("PROCESSING");
        Mockito.when(fileMapper.selectById(row.getId())).thenReturn(row);

        service.processVideoWatermark(row.getId(), UUID.randomUUID());

        Mockito.verify(processor, Mockito.never()).applyTo(Mockito.any(), Mockito.any(), any(), any());
        Mockito.verify(fileMapper, Mockito.never()).publishProcessed(any(), any(), any(), any(),
            Mockito.anyLong(), any(), any());
    }

    /** 转码后的成品超过上限 → 失败且原因可读，**绝不**把超大对象塞进存储（输入上限只管源文件）。 */
    @Test
    void overlyLargeTranscodedOutputFailsWithAReadableReason() throws Exception {
        MobileFileProperties properties = new MobileFileProperties();
        properties.setMaxVideoBytes(4);
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, backgroundTasks::add);
        Mockito.when(processor.applyTo(Mockito.any(), Mockito.any(), any(), any()))
            .thenReturn(writeProcessed(new byte[] {1, 2, 3, 4, 5}));
        Mockito.when(fileMapper.renewProcessingClaim(any(), any())).thenReturn(1);
        MobileFile row = existingFile(UUID.randomUUID(), 7L);
        row.setStatus("PROCESSING");
        row.setContentType("video/mp4");
        Mockito.when(fileMapper.selectById(row.getId())).thenReturn(row);

        service.processVideoWatermark(row.getId(), UUID.randomUUID());

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        Mockito.verify(fileMapper).failProcessing(Mockito.eq(row.getId()), any(), error.capture());
        assertThat(error.getValue()).contains("大小上限");
        assertThat(storage.putCount).isZero();
    }

    /**
     * OOM 之类 Error 也要把行写成人话状态，**并且不能被吞掉**——原来 `catch (Exception)` 接不住
     * Error，行会永远停在 PROCESSING，连重启都有可能再炸一次。
     */
    @Test
    void processingErrorMarksRowFailedAndIsRethrown() throws Exception {
        MobileFileProperties properties = new MobileFileProperties();
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, backgroundTasks::add);
        Mockito.when(processor.applyTo(Mockito.any(), Mockito.any(), any(), any()))
            .thenThrow(new OutOfMemoryError("Java heap space"));
        Mockito.when(fileMapper.renewProcessingClaim(any(), any())).thenReturn(1);
        MobileFile row = existingFile(UUID.randomUUID(), 7L);
        row.setStatus("PROCESSING");
        Mockito.when(fileMapper.selectById(row.getId())).thenReturn(row);

        assertThatThrownBy(() -> service.processVideoWatermark(row.getId(), UUID.randomUUID()))
            .isInstanceOf(OutOfMemoryError.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        Mockito.verify(fileMapper).failProcessing(Mockito.eq(row.getId()), any(), error.capture());
        assertThat(error.getValue()).contains("OutOfMemoryError");
    }

    /** reaper：有界、先认领；队列满时只把租约还回去，**绝不**标 FAILED（那是上传路径的语义）。 */
    @Test
    void reaperLeavesRowProcessingWhenQueueIsFull() {
        backgroundTasks.clear();
        MobileFileProperties properties = new MobileFileProperties();
        Executor rejecting = command -> { throw new RejectedExecutionException("full"); };
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, rejecting);
        UUID stale = UUID.randomUUID();
        Mockito.when(fileMapper.claimStaleProcessing(any(), Mockito.eq(20), any()))
            .thenReturn(java.util.List.of(stale));

        service.reapStaleProcessing();

        Mockito.verify(fileMapper).releaseProcessingClaim(Mockito.eq(stale), any());
        Mockito.verify(fileMapper, Mockito.never()).failProcessing(any(), any(), any());
    }

    /** 对照：同一条路走上传，被拒就是 FAILED（客户端在轮询，得立刻知道）。 */
    @Test
    void uploadPathMarksFailedWhenQueueIsFull() throws Exception {
        Executor rejecting = command -> { throw new RejectedExecutionException("full"); };
        MobileFileProperties properties = new MobileFileProperties();
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, rejecting);
        Mockito.when(processor.supports("video/quicktime")).thenReturn(true);
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);
        Mockito.when(fileMapper.claimProcessing(any(), any())).thenReturn(1);

        service.upload(new MockMultipartFile("file", "clip.mov", "video/quicktime", movBytes()),
            7L, true, "AntFlow");

        Mockito.verify(fileMapper).failProcessing(any(), any(), Mockito.contains("queue"));
        Mockito.verify(fileMapper, Mockito.never()).releaseProcessingClaim(any(), any());
    }

    /** 处理器现在只吃文件路径：给测试准备一个"成品文件"。 */
    private static Path writeProcessed(byte[] content) throws IOException {
        Path dir = Files.createTempDirectory("antflow-test-wm-");
        Path output = dir.resolve("output.mp4");
        Files.write(output, content);
        return output;
    }

    /**
     * 要水印却把文案丢了：以前静默存原图（调用方以为加了水印），现在明说——不然开了水印的字段
     * 能悄悄产出没有水印的"证据照片"。
     */
    @Test
    void uploadRejectsWatermarkWithoutText() {
        Mockito.when(processor.supports("image/png")).thenReturn(true);

        assertThatThrownBy(() -> service.upload(pngFile("logo.png", pngBytes()), 7L, true, "  "))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("水印文案");
        assertThat(storage.putCount).isZero();
        Mockito.verify(processor, Mockito.never()).applyTo(Mockito.any(), Mockito.any(), Mockito.any(),
            Mockito.any());
    }

    @Test
    void uploadDeduplicatesAndRepairsExistingStorageObject() throws Exception {
        MobileFile existing = existingFile(UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60"), 7L);
        Mockito.when(fileMapper.selectOne(any())).thenReturn(existing);

        byte[] originalBytes = pngBytes();
        MobileFileDto dto = service.upload(pngFile("logo.png", originalBytes), 7L);

        assertThat(dto.id()).isEqualTo(existing.getId());
        assertThat(dto.contentUrl()).isEqualTo("/api/mobile/files/" + existing.getId() + "/content");
        assertThat(storage.putCount).isEqualTo(1);
        assertThat(storage.storageKey).isEqualTo(existing.getStorageKey());
        assertThat(storage.contentBytes).isEqualTo(originalBytes);
        Mockito.verify(fileMapper, Mockito.never()).insert(any(MobileFile.class));
    }

    /** 对象已经在存储里了：重复上传只返回旧行，不再把同一份字节往 MinIO 重写一遍。 */
    @Test
    void uploadDeduplicatesWithoutRewritingWhenObjectExists() throws Exception {
        MobileFile existing = existingFile(UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60"), 7L);
        Mockito.when(fileMapper.selectOne(any())).thenReturn(existing);
        storage.existsResult = true;

        MobileFileDto dto = service.upload(pngFile("logo.png", pngBytes()), 7L);

        assertThat(dto.id()).isEqualTo(existing.getId());
        assertThat(storage.putCount).isZero();
        Mockito.verify(fileMapper, Mockito.never()).insert(any(MobileFile.class));
    }

    /**
     * 存储探针失败 ≠ 对象缺失 ≠ 文件有问题：网络抖动时整个请求必须失败在"存储"这一步。
     * 被当成"缺失"就会每次抖动都白重传一份；被当成 BAD_FILE 会把锅甩给用户的文件。
     */
    @Test
    void uploadFailsWithStorageErrorWhenProbeCannotReachStorage() throws Exception {
        MobileFile existing = existingFile(UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60"), 7L);
        Mockito.when(fileMapper.selectOne(any())).thenReturn(existing);
        storage.failExists = true;

        assertThatThrownBy(() -> service.upload(pngFile("logo.png", pngBytes()), 7L))
            .isInstanceOf(BizException.class)
            .satisfies(exception -> assertThat(((BizException) exception).getCode())
                .isEqualTo("FILE_STORAGE_FAILED"));
        assertThat(storage.putCount).isZero();
    }

    /**
     * 上限不能只看声明的大小：声明 3 字节、实际给 5 字节，客户端就能把 `maxBytes` 绕过去
     * （代价落在磁盘）。真实字节数只有 stage 数完才知道，所以落盘后必须再比一次。
     */
    @Test
    void uploadRejectsRealBytesOverLimitWhenDeclaredSizeIsLiedAbout() throws Exception {
        MobileFileProperties properties = new MobileFileProperties();
        properties.setMaxBytes(4);
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService, backgroundTasks::add);
        MultipartFile lying = Mockito.mock(MultipartFile.class);
        Mockito.when(lying.isEmpty()).thenReturn(false);
        Mockito.when(lying.getSize()).thenReturn(3L);
        Mockito.when(lying.getContentType()).thenReturn("image/png");
        Mockito.when(lying.getOriginalFilename()).thenReturn("photo.png");
        Mockito.when(lying.getInputStream())
            .thenAnswer(invocation -> new ByteArrayInputStream(pngBytes()));

        assertThatThrownBy(() -> service.upload(lying, 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("file is too large");
        assertThat(storage.putCount).isZero();
        Mockito.verify(fileMapper, Mockito.never()).insert(any(MobileFile.class));
    }

    @Test
    void uploadStoresValidatedFileMetadata() throws Exception {
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);

        byte[] originalBytes = pngBytes();
        MobileFileDto dto = service.upload(pngFile("logo.png", originalBytes), 7L);

        assertThat(dto.name()).isEqualTo("logo.png");
        assertThat(dto.contentType()).isEqualTo("image/png");
        assertThat(dto.contentUrl()).startsWith("/api/mobile/files/");
        assertThat(storage.putCount).isEqualTo(1);
        assertThat(storage.contentBytes).isEqualTo(originalBytes);
        ArgumentCaptor<MobileFile> captor = ArgumentCaptor.forClass(MobileFile.class);
        Mockito.verify(fileMapper).insert(captor.capture());
        MobileFile row = captor.getValue();
        assertThat(row.getOwnerId()).isEqualTo(7L);
        assertThat(row.getOriginalName()).isEqualTo("logo.png");
        assertThat(row.getStorageKey()).contains(row.getId().toString());
        assertThat(row.getStorageKey()).startsWith("image/");
        assertThat(row.getSha256()).hasSize(64);
        assertThat(row.getStatus()).isEqualTo("READY");
        assertThat(storage.contentType).isEqualTo("image/png");
    }

    @Test
    void ownerCanReadMetadata() {
        UUID id = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        Mockito.when(fileMapper.selectById(id)).thenReturn(existingFile(id, 7L));

        MobileFileDto dto = service.getMetadata(id, 7L, List.of("user"));

        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.name()).isEqualTo("logo.png");
        // 自己的文件直接放行：不该再去查关联实例（一页 20 张图就是几十次多余查询）
        Mockito.verify(accessMapper, Mockito.never()).selectLinkedInstanceIds(any());
    }

    @Test
    void unrelatedUserCannotReadMetadata() {
        UUID id = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        Mockito.when(fileMapper.selectById(id)).thenReturn(existingFile(id, 7L));

        assertThatThrownBy(() -> service.getMetadata(id, 8L, List.of("user")))
            .isInstanceOf(HiddenResourceException.class);
    }

    @Test
    void historicalParticipantCannotReadLinkedFileMetadataWithoutFullVisibility() {
        UUID id = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        Mockito.when(fileMapper.selectById(id)).thenReturn(existingFile(id, 7L));
        Mockito.when(accessMapper.selectLinkedInstanceIds(id)).thenReturn(List.of(501L));
        Mockito.when(authorizationService.canReadFullInstance(501L, 8L)).thenReturn(false);

        assertThatThrownBy(() -> service.getMetadata(id, 8L, List.of("user")))
            .isInstanceOf(HiddenResourceException.class);
    }

    @Test
    void adminCanReadMetadata() {
        UUID id = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        Mockito.when(fileMapper.selectById(id)).thenReturn(existingFile(id, 7L));

        MobileFileDto dto = service.getMetadata(id, 99L, List.of("admin"));

        assertThat(dto.id()).isEqualTo(id);
        Mockito.verify(accessMapper, Mockito.never()).selectLinkedInstanceIds(any());
    }

    /**
     * 镜像里没装 ffmpeg 时（本地 docker 栈曾经就是这样）**图片**上传会同步失败——这里用真 processor
     * 走一遍，钉住"用户看到的是能照着做的话"，而不是 `Cannot run program "ffmpeg"`。
     */
    @Test
    void imageWatermarkWithMissingBinaryFailsWithAnActionableMessage() throws Exception {
        MobileMediaProperties mediaProperties = new MobileMediaProperties();
        mediaProperties.setFfmpegBin("antflow-no-such-ffmpeg-binary");
        service = new MobileFileService(fileMapper, accessMapper, storage,
            new MobileFileProperties(), new MediaWatermarkProcessor(mediaProperties),
            authorizationService, backgroundTasks::add);

        assertThatThrownBy(() -> service.upload(
            new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes()),
            7L, true, "AntFlow"))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("ffmpeg")
            .hasMessageNotContaining("Cannot run program");
        assertThat(storage.putCount).isZero();
    }

    /** 声明成 image/png 的非图片字节：以前直接放行、还会走进 ffmpeg；现在按内容拒绝。 */
    @Test
    void imageUploadRejectsBytesThatAreNotAnImage() {
        assertThatThrownBy(() -> service.upload(
            new MockMultipartFile("file", "fake.png", "image/png",
                "%PDF-1.7 not really an image".getBytes(StandardCharsets.US_ASCII)), 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("不是支持的图片格式");
        assertThat(storage.putCount).isZero();
    }

    /**
     * 但**真图片都得放行**：iPhone 的 HEIC（`convertHeic` 默认没开，是原样上传的）、安卓的 AVIF、
     * 扫描件的 TIFF、以及 SVG。漏一个就是"用户选张照片被判成不是图片"。
     */
    @Test
    void imageUploadAcceptsEveryCommonImageSignature() throws Exception {
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);
        byte[][] contents = {
            jpegBytes(),
            pngBytes(),
            "GIF89a".getBytes(StandardCharsets.US_ASCII),
            concat(ascii("RIFF"), new byte[] {0, 0, 0, 0}, ascii("WEBP")),
            ascii("BM00000000"),
            new byte[] {'I', 'I', 0x2A, 0x00, 1, 2, 3},
            concat(new byte[] {0, 0, 0, 0x18}, ascii("ftypheic"), new byte[] {0, 0, 0, 0}),
            concat(new byte[] {0, 0, 0, 0x18}, ascii("ftypavif"), new byte[] {0, 0, 0, 0}),
            ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"),
        };
        for (byte[] content : contents) {
            assertThat(service.upload(new MockMultipartFile("file", "photo.jpg", "image/jpeg", content), 7L))
                .as("内容应以图片通过校验")
                .isNotNull();
        }
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    @Test
    void deleteRejectsSubmittedLinkedFile() {
        UUID id = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        lockFile(existingFile(id, 7L));
        Mockito.when(accessMapper.countLinks(id)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(id, 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("file already submitted");
        Mockito.verify(fileMapper, Mockito.never()).updateById(any(MobileFile.class));
    }

    /** 处理中的视频不给删：后台正往同一个 key 写结果，删了会留下读不出来的孤儿对象。 */
    @Test
    void deleteRejectsFileStillBeingProcessed() {
        UUID id = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        MobileFile row = existingFile(id, 7L);
        row.setStatus("PROCESSING");
        lockFile(row);

        assertThatThrownBy(() -> service.delete(id, 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("处理完再删");
        Mockito.verify(fileMapper, Mockito.never()).updateById(any(MobileFile.class));
        assertThat(storage.deletedKeys).isEmpty();
    }

    /**
     * 对象删除在事务提交后执行：否则"删了对象、事务却回滚"会留下 READY 行指向不存在的对象；
     * 反过来（提交成功、删对象失败）最多是个没人引用的孤儿。
     */
    @Test
    void deleteRemovesTheObjectOnlyAfterCommit() {
        UUID id = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        MobileFile row = existingFile(id, 7L);
        lockFile(row);
        org.springframework.transaction.support.TransactionSynchronizationManager
            .initSynchronization();
        try {
            service.delete(id, 7L);
            // 事务还没提交 → 对象还没删
            assertThat(storage.deletedKeys).isEmpty();
            org.springframework.transaction.support.TransactionSynchronizationManager
                .getSynchronizations().forEach(
                    org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            assertThat(storage.deletedKeys).contains(row.getStorageKey());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager
                .clearSynchronization();
        }
    }

    /** delete 现在拿的是行锁（FOR UPDATE），测试里让批量加锁返回那一行。 */
    private void lockFile(MobileFile row) {
        Mockito.when(fileMapper.selectByIdsForUpdate(any())).thenReturn(java.util.List.of(row));
    }

    private static MockMultipartFile pngFile(String name, byte[] content) {
        return new MockMultipartFile("file", name, "image/png", content);
    }

    private static byte[] jpegBytes() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return output.toByteArray();
    }

    private static byte[] pngBytes() {
        try {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0x0B57D0);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("could not create test png", exception);
        }
    }

    private static byte[] mp4Bytes() {
        return new byte[] {
            0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0,
            'i', 's', 'o', 'm'
        };
    }

    private static byte[] movBytes() {
        return new byte[] {
            0, 0, 0, 20, 'f', 't', 'y', 'p', 'q', 't', ' ', ' ', 0, 0, 0, 0,
            'q', 't', ' ', ' '
        };
    }

    private static byte[] pdfBytes() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF"
            .getBytes(StandardCharsets.US_ASCII);
    }

    private static MobileFile existingFile(UUID id, long ownerId) {
        MobileFile file = new MobileFile();
        file.setId(id);
        file.setOwnerId(ownerId);
        file.setOriginalName("logo.png");
        file.setStorageKey(ownerId + "/" + id + "-logo.png");
        file.setContentType("image/png");
        file.setSizeBytes(9L);
        file.setSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        file.setStatus("READY");
        return file;
    }

    private static final class CapturingStorage implements FileStorage {
        private int putCount;
        private String storageKey;
        private String contentType;
        private byte[] contentBytes = new byte[0];
        /** 对象在不在：默认 false，让"缺了就补写"的既有语义不变。 */
        private boolean existsResult;
        /** 模拟"存储查不动"（网络抖动/权限）：必须原样失败，不能退化成"缺失"或 BAD_FILE。 */
        private boolean failExists;
        /** 被删掉的 key：断言"旧对象在发布后清理""失效的 attempt 被丢弃"用。 */
        private final java.util.List<String> deletedKeys = new java.util.ArrayList<>();

        @Override
        public StoredObject put(String storageKey, InputStream content, long size,
                                String contentType) throws IOException {
            putCount++;
            this.storageKey = storageKey;
            this.contentType = contentType;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            content.transferTo(captured);
            contentBytes = captured.toByteArray();
            return new StoredObject(storageKey, size);
        }

        @Override
        public org.springframework.core.io.Resource get(String storageKey) {
            return new org.springframework.core.io.InputStreamResource(
                new ByteArrayInputStream(contentBytes));
        }

        @Override
        public boolean exists(String storageKey) {
            if (failExists) {
                throw new BizException("FILE_STORAGE_FAILED", "could not check object in MinIO");
            }
            return existsResult;
        }

        @Override
        public void delete(String storageKey) {
            deletedKeys.add(storageKey);
        }
    }
}
