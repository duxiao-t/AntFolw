package com.antflow.attachment;

import com.antflow.engine.BizException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;

/**
 * 删除还没提交的附件（软删行 + 提交后删对象）。
 *
 * <p>从 {@code MobileFileService} 原样搬出（方法体逐字未改）。**方法本身不带 {@code @Transactional}**：
 * 事务边界留在 {@link MobileFileService#delete}（它是 Spring 代理的入口，搬到非 bean 的这里注解会静默失效）。
 */
@RequiredArgsConstructor
class MobileFileDeletionService {
    private static final String PROCESSING_STATUS = "PROCESSING";
    private static final String DELETED_STATUS = "DELETED";

    private final MobileFileMapper fileMapper;
    private final MobileFileAccessMapper accessMapper;
    private final MobileFileObjectStore objects;

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
    void delete(UUID id, long userId) {
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
        objects.deleteAfterCommit(file.getStorageKey());
    }
}
