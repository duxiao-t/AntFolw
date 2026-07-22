package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class MobileFileService {
    private static final String READY_STATUS = "READY";
    private static final String DELETED_STATUS = "DELETED";
    private static final int JPEG_SIGNATURE_SIZE = 3;

    private final MobileFileMapper fileMapper;
    private final MobileFileAccessMapper accessMapper;
    private final FileStorage storage;
    private final MobileFileProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public MobileFileDto upload(MultipartFile file, long ownerId) {
        validateBasic(file);
        byte[] content = readAllBytes(file);
        String submittedContentType = normalize(file.getContentType());
        validateContent(submittedContentType, content);
        String sha256 = sha256(content);
        MobileFile existing = fileMapper.selectOne(new QueryWrapper<MobileFile>()
            .eq("owner_id", ownerId)
            .eq("sha256", sha256)
            .eq("status", READY_STATUS)
            .isNull("deleted_at"));
        if (existing != null) {
            return toDto(existing);
        }

        UUID id = UUID.randomUUID();
        String originalName = sanitizeName(file.getOriginalFilename());
        String storageKey = ownerId + "/" + id + "-" + originalName;
        try {
            storage.put(storageKey, new ByteArrayInputStream(content), content.length);
        } catch (IOException exception) {
            throw new BizException("FILE_STORAGE_FAILED", exception.getMessage());
        }

        MobileFile row = new MobileFile();
        row.setId(id);
        row.setOwnerId(ownerId);
        row.setOriginalName(originalName);
        row.setStorageKey(storageKey);
        row.setContentType(submittedContentType);
        row.setSizeBytes((long) content.length);
        row.setSha256(sha256);
        row.setStatus(READY_STATUS);
        fileMapper.insert(row);
        return toDto(row);
    }

    public MobileFileDto getMetadata(UUID id, long userId, List<String> roles) {
        return toDto(requireReadable(id, userId, roles));
    }

    public MobileFileContent readContent(UUID id, long userId, List<String> roles) {
        MobileFile file = requireReadable(id, userId, roles);
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
            throw new BizException("FILE_STORAGE_FAILED", exception.getMessage());
        }
    }

    private void validateBasic(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw new BizException("BAD_FILE", "file is empty");
        }
        if (file.getSize() > properties.getMaxBytes()) {
            throw new BizException("BAD_FILE", "file is too large");
        }
        String contentType = normalize(file.getContentType());
        if (!properties.getAllowedTypes().contains(contentType)) {
            throw new BizException("BAD_FILE", "unsupported content type");
        }
    }

    private byte[] readAllBytes(MultipartFile file) {
        try {
            return file.getInputStream().readAllBytes();
        } catch (IOException exception) {
            throw new BizException("BAD_FILE", exception.getMessage());
        }
    }

    private void validateContent(String submittedContentType, byte[] content) {
        if (hasExecutableSignature(content)) {
            throw new BizException("BAD_FILE", "unsupported file content");
        }
        String detectedContentType = detectContentType(content);
        if (detectedContentType == null) {
            throw new BizException("BAD_FILE", "unsupported file content");
        }
        if (!submittedContentType.equals(detectedContentType)) {
            throw new BizException("BAD_FILE", "content type mismatch");
        }
    }

    private static boolean hasExecutableSignature(byte[] content) {
        return startsWith(content, new byte[] {0x4D, 0x5A})
            || startsWith(content, new byte[] {0x7F, 0x45, 0x4C, 0x46});
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
        return null;
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

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalize(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
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

    private MobileFile requireReadable(UUID id, long userId, List<String> roles) {
        MobileFile file = requireExisting(id);
        boolean admin = roles != null && roles.contains("admin");
        boolean owner = Objects.equals(file.getOwnerId(), userId);
        if (admin || owner || accessMapper.countReadableProcessLinks(id, userId) > 0) {
            return file;
        }
        throw new AccessDeniedException("file is not readable");
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
            "/api/mobile/files/" + file.getId() + "/content"
        );
    }
}
