package com.antflow.attachment;

import com.antflow.authz.AuthorizationService;
import com.antflow.authz.HiddenResourceException;
import com.antflow.engine.BizException;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/**
 * 读取侧的元数据与内容：先判"这个人能不能看"，再判"文件好了没"。
 *
 * <p>从 {@code MobileFileService} 原样搬出（方法体逐字未改）。可见性规则没动：admin 直接放行、
 * 自己且未被引用的直接放行、被自己有权读的实例/表单数据引用的放行，其余一律
 * {@link HiddenResourceException}（**不是** 404 也不是 403——存在性本身也要藏）。
 */
@RequiredArgsConstructor
class MobileFileReader {
    private static final String READY_STATUS = "READY";
    private static final String DELETED_STATUS = "DELETED";
    private static final String FAILED_STATUS = "FAILED";
    /** 给客户端的失败原因最多多少字（客户端要保持可读，不搬整段 ffmpeg 报错）。 */
    private static final int USER_REASON_CHARS = 200;

    private final MobileFileMapper fileMapper;
    private final MobileFileAccessMapper accessMapper;
    private final FileStorage storage;
    private final AuthorizationService authorizationService;

    MobileFileDto getMetadata(UUID id, long userId, java.util.Collection<String> roles) {
        return toDto(requireReadable(id, userId, roles));
    }

    MobileFileContent readContent(UUID id, long userId, java.util.Collection<String> roles) {
        MobileFile file = requireReadable(id, userId, roles);
        if (!READY_STATUS.equals(file.getStatus())) {
            throw new BizException("FILE_PROCESSING", "file is not ready");
        }
        return new MobileFileContent(toDto(file), storage.get(file.getStorageKey()));
    }

    private MobileFile requireReadable(UUID id, long userId, java.util.Collection<String> roles) {
        MobileFile file = requireExisting(id);
        boolean admin = roles != null && roles.contains("admin");
        boolean owner = Objects.equals(file.getOwnerId(), userId);
        if (admin) return file;
        if (owner && accessMapper.countLinks(id) == 0) return file;
        boolean linkedInstanceReadable = accessMapper.selectLinkedInstanceIds(id).stream()
            .anyMatch(instanceId -> authorizationService.canReadFullInstance(instanceId, userId));
        if (linkedInstanceReadable) return file;
        if (accessMapper.selectLinkedFormDataIds(id).stream()
            .anyMatch(dataId -> authorizationService.canReadFormData(dataId, userId))) return file;
        throw new HiddenResourceException("file not found");
    }

    private MobileFile requireExisting(UUID id) {
        MobileFile file = fileMapper.selectById(id);
        if (file == null || DELETED_STATUS.equals(file.getStatus()) || file.getDeletedAt() != null) {
            throw new BizException("FILE_NOT_FOUND", "file not found");
        }
        return file;
    }

    /** 上传与读取共用同一个视图：两处各拼一份 DTO 迟早会漂移（尤其 failureReason 的脱敏口径）。 */
    static MobileFileDto toDto(MobileFile file) {
        return new MobileFileDto(
            file.getId(),
            file.getOriginalName(),
            file.getContentType(),
            file.getSizeBytes(),
            "/api/mobile/files/" + file.getId() + "/content",
            file.getStatus(),
            // 失败原因只在 FAILED 时给（已在写入时就脱敏过），客户端轮询到 FAILED 才有的可展示。
            FAILED_STATUS.equals(file.getStatus())
                ? MobileFileStaging.truncate(file.getProcessingError(), USER_REASON_CHARS) : null,
            file.getDurationSeconds()
        );
    }
}
