package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

class MobileFileServiceTest {
    private MobileFileMapper fileMapper;
    private MobileFileAccessMapper accessMapper;
    private CapturingStorage storage;
    private MobileFileService service;

    @BeforeEach
    void setUp() {
        fileMapper = Mockito.mock(MobileFileMapper.class);
        accessMapper = Mockito.mock(MobileFileAccessMapper.class);
        storage = new CapturingStorage();
        MobileFileProperties properties = new MobileFileProperties();
        properties.setMaxBytes(10L * 1024 * 1024);
        properties.setAllowedTypes(List.of("image/jpeg", "image/png", "application/pdf"));
        service = new MobileFileService(fileMapper, accessMapper, storage, properties);
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
        properties.setAllowedTypes(List.of("image/png"));
        service = new MobileFileService(fileMapper, accessMapper, storage, properties);
        MockMultipartFile file = pngFile("large.png", new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D
        });

        assertThatThrownBy(() -> service.upload(file, 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("file is too large");
        assertThat(storage.putCount).isZero();
    }

    @Test
    void uploadRejectsExecutableSignature() {
        MockMultipartFile file = new MockMultipartFile("file", "run.png", "image/png", new byte[] {
            0x4D, 0x5A, 0x00, 0x00
        });

        assertThatThrownBy(() -> service.upload(file, 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("unsupported file content");
    }

    @Test
    void uploadRejectsMismatchedContentType() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", "%PDF-1.7".getBytes());

        assertThatThrownBy(() -> service.upload(file, 7L))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("content type mismatch");
    }

    @Test
    void uploadDeduplicatesSameOwnerAndContent() throws Exception {
        MobileFile existing = existingFile(UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60"), 7L);
        Mockito.when(fileMapper.selectOne(any())).thenReturn(existing);

        MobileFileDto dto = service.upload(pngFile("logo.png", pngBytes()), 7L);

        assertThat(dto.id()).isEqualTo(existing.getId());
        assertThat(dto.contentUrl()).isEqualTo("/api/mobile/files/" + existing.getId() + "/content");
        assertThat(storage.putCount).isZero();
        Mockito.verify(fileMapper, Mockito.never()).insert(any(MobileFile.class));
    }

    @Test
    void uploadStoresValidatedFileMetadata() throws Exception {
        Mockito.when(fileMapper.selectOne(any())).thenReturn(null);

        MobileFileDto dto = service.upload(pngFile("logo.png", pngBytes()), 7L);

        assertThat(dto.name()).isEqualTo("logo.png");
        assertThat(dto.contentType()).isEqualTo("image/png");
        assertThat(dto.contentUrl()).startsWith("/api/mobile/files/");
        assertThat(storage.putCount).isEqualTo(1);
        ArgumentCaptor<MobileFile> captor = ArgumentCaptor.forClass(MobileFile.class);
        Mockito.verify(fileMapper).insert(captor.capture());
        MobileFile row = captor.getValue();
        assertThat(row.getOwnerId()).isEqualTo(7L);
        assertThat(row.getOriginalName()).isEqualTo("logo.png");
        assertThat(row.getStorageKey()).contains(row.getId().toString());
        assertThat(row.getSha256()).hasSize(64);
        assertThat(row.getStatus()).isEqualTo("READY");
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
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void processParticipantCanReadLinkedFileMetadata() {
        UUID id = UUID.fromString("d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60");
        Mockito.when(fileMapper.selectById(id)).thenReturn(existingFile(id, 7L));
        Mockito.when(accessMapper.countReadableProcessLinks(id, 8L)).thenReturn(1L);

        MobileFileDto dto = service.getMetadata(id, 8L, List.of("user"));

        assertThat(dto.id()).isEqualTo(id);
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

    private static byte[] pngBytes() {
        return new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01
        };
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

        @Override
        public StoredObject put(String storageKey, InputStream content, long size) throws IOException {
            putCount++;
            content.transferTo(OutputStream.nullOutputStream());
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
