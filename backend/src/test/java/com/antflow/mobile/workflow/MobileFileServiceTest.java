package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.engine.BizException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

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

    @BeforeEach
    void setUp() {
        fileMapper = Mockito.mock(MobileFileMapper.class);
        accessMapper = Mockito.mock(MobileFileAccessMapper.class);
        storage = new CapturingStorage();
        processor = Mockito.mock(MediaWatermarkProcessor.class);
        authorizationService = Mockito.mock(AuthorizationService.class);
        MobileFileProperties properties = new MobileFileProperties();
        properties.setMaxBytes(10L * 1024 * 1024);
        service = new MobileFileService(fileMapper, accessMapper, storage, properties, processor,
            authorizationService);
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
            authorizationService);
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
            authorizationService);

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
            authorizationService);
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
            authorizationService);
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);
        Mockito.when(processor.supports("video/quicktime")).thenReturn(true);
        Mockito.when(processor.apply(Mockito.any(), Mockito.eq("video/quicktime"), Mockito.eq("AntFlow")))
            .thenReturn(new byte[] {1, 2, 3});
        Mockito.when(processor.resultContentType("video/quicktime")).thenReturn("video/mp4");

        MobileFileDto dto = service.upload(
            new MockMultipartFile("file", "clip.mov", "video/quicktime", movBytes()), 7L, true, "AntFlow");

        assertThat(dto.contentType()).isEqualTo("video/mp4");
        assertThat(dto.name()).isEqualTo("clip.mp4");
        assertThat(storage.contentBytes).isEqualTo(new byte[] {1, 2, 3});
        assertThat(storage.contentType).isEqualTo("video/mp4");
    }

    @Test
    void uploadSkipsWatermarkWhenTextIsBlank() throws Exception {
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);
        Mockito.when(processor.supports("image/png")).thenReturn(true);

        byte[] content = pngBytes();
        MobileFileDto dto = service.upload(pngFile("logo.png", content), 7L, true, "  ");

        assertThat(storage.contentBytes).isEqualTo(content);
        Mockito.verify(processor, Mockito.never()).apply(Mockito.any(), Mockito.any(), Mockito.any());
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
    }

    @Test
    void deleteRejectsSubmittedLinkedFile() {
        UUID id = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        Mockito.when(fileMapper.selectById(id)).thenReturn(existingFile(id, 7L));
        Mockito.when(accessMapper.countLinks(id)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(id, 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("file already submitted");
        Mockito.verify(fileMapper, Mockito.never()).updateById(any(MobileFile.class));
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
            return new org.springframework.core.io.InputStreamResource(new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public void delete(String storageKey) {
        }
    }
}
