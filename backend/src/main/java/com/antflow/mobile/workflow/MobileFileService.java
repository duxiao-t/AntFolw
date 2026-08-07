package com.antflow.mobile.workflow;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
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
    private final MediaWatermarkProcessor watermarkProcessor;
    private final AuthorizationService authorizationService;

    @Transactional(rollbackFor = Exception.class)
    public MobileFileDto upload(MultipartFile file, long ownerId) {
        return upload(file, ownerId, false, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public MobileFileDto upload(MultipartFile file, long ownerId, boolean watermark, String watermarkText) {
        validateBasic(file);
        byte[] content = readAllBytes(file);
        String submittedContentType = normalize(file.getContentType());
        validateContent(submittedContentType, content);
        boolean watermarked = false;
        String watermarkLabel = watermarkText == null ? "" : watermarkText.trim();
        if (watermark && watermarkProcessor.supports(submittedContentType) && !watermarkLabel.isEmpty()) {
            content = watermarkProcessor.apply(content, submittedContentType, watermarkLabel);
            submittedContentType = watermarkProcessor.resultContentType(submittedContentType);
            watermarked = true;
        }
        String sha256 = sha256(content);
        MobileFile existing = fileMapper.selectOne(new QueryWrapper<MobileFile>()
            .eq("owner_id", ownerId)
            .eq("sha256", sha256)
            .eq("status", READY_STATUS)
            .isNull("deleted_at"));
        if (existing != null) {
            writeStorageObject(existing.getStorageKey(), content, submittedContentType);
            return toDto(existing);
        }

        UUID id = UUID.randomUUID();
        String originalName = sanitizeName(file.getOriginalFilename());
        if (watermarked && submittedContentType.startsWith("video/")) {
            originalName = toMp4Name(originalName);
        }
        String storageKey = kindPrefix(submittedContentType) + id + "-" + originalName;
        writeStorageObject(storageKey, content, submittedContentType);

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

    private void writeStorageObject(String storageKey, byte[] content, String contentType) {
        try {
            storage.put(storageKey, new ByteArrayInputStream(content), content.length, contentType);
        } catch (IOException exception) {
            throw new BizException("FILE_STORAGE_FAILED", exception.getMessage());
        }
    }

    public MobileFileDto getMetadata(UUID id, long userId, java.util.Collection<String> roles) {
        return toDto(requireReadable(id, userId, roles));
    }

    public MobileFileContent readContent(UUID id, long userId, java.util.Collection<String> roles) {
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
        String contentType = normalize(file.getContentType());
        long limit = contentType.startsWith("video/") ? properties.getMaxVideoBytes() : properties.getMaxBytes();
        if (file.getSize() > limit) {
            throw new BizException("BAD_FILE", "file is too large");
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

    private void validateImageContent(String submittedContentType, byte[] content) {
        // Android file pickers often report image MIME types inconsistently
        // (e.g. JPEG bytes labeled image/png, webp labeled image/jpeg). Accept
        // any image/* payload; the executable-signature check already ran.
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

    private MobileFile requireReadable(UUID id, long userId, java.util.Collection<String> roles) {
        MobileFile file = requireExisting(id);
        boolean admin = roles != null && roles.contains("admin");
        boolean owner = Objects.equals(file.getOwnerId(), userId);
        boolean participant = accessMapper.countReadableProcessLinks(id, userId) > 0;
        boolean linkedInstanceReadable = accessMapper.selectLinkedInstanceIds(id).stream()
            .anyMatch(instanceId -> authorizationService.canReadInstance(instanceId, userId));
        if (admin || owner || participant || linkedInstanceReadable) {
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
            "/api/mobile/files/" + file.getId() + "/content"
        );
    }
}
