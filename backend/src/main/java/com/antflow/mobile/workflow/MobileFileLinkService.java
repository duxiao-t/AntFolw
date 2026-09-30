package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MobileFileLinkService {
    private final MobileWorkflowMapper workflowMapper;
    private final MobileFileMapper fileMapper;

    public void append(Long formDataId, List<MobileFileRef> refs, long userId) {
        for (MobileFileRef ref : normalized(refs, userId)) {
            workflowMapper.insertFileLink(formDataId, ref.fileId(), ref.fieldId(), ref.sortOrder());
        }
    }

    public void reconcile(Long formDataId, List<MobileFileRef> refs, long userId) {
        List<MobileFileRef> normalized = normalized(refs, userId);
        workflowMapper.deleteFileLinks(formDataId);
        for (MobileFileRef ref : normalized) {
            workflowMapper.insertFileLink(formDataId, ref.fileId(), ref.fieldId(), ref.sortOrder());
        }
    }

    public void reconcileEditable(Long formDataId, List<MobileFileRef> refs, long userId,
                                  Map<String, String> fieldModes) {
        List<MobileFileRef> editable = normalized((refs == null ? List.<MobileFileRef>of() : refs)
            .stream()
            .filter(ref -> ref != null && "EDITABLE".equals(
                fieldModes.getOrDefault(ref.fieldId(), "EDITABLE")))
            .toList(), userId);
        List<MobileWorkflowMapper.FormDataFileLink> restricted =
            workflowMapper.selectFileLinks(formDataId).stream()
                .filter(link -> !"EDITABLE".equals(
                    fieldModes.getOrDefault(link.fieldId(), "EDITABLE")))
                .toList();
        workflowMapper.deleteFileLinks(formDataId);
        for (MobileWorkflowMapper.FormDataFileLink link : restricted) {
            workflowMapper.insertFileLink(formDataId, link.fileId(), link.fieldId(),
                link.sortOrder());
        }
        for (MobileFileRef ref : editable) {
            workflowMapper.insertFileLink(formDataId, ref.fileId(), ref.fieldId(),
                ref.sortOrder());
        }
    }

    /**
     * 校验 + 按 `fileId` 去重 + **加行锁复核**。
     *
     * <p>三件事不能省：
     * <ul>
     *   <li>**行锁**：`delete` 也会锁同一行。不加锁的话"检查文件是否 READY"和"删除"会交错，
     *       结果是提交成功、附件指向已删文件（永久坏引用）。锁按 id 升序批量取，避免与并发删除死锁。</li>
     *   <li>**按 `fileId` 去重**：`t_form_data_file` 的主键是 `(form_data_id, file_id)`，而调用方可能
     *       给出同一文件的多个字段（上传端的 SHA 去重会让两张不同字段的照片落到同一个 id）——
     *       按 fileId+fieldId 去重就会两次插入同一主键、整单 500。</li>
     *   <li>**在锁内复核状态**：拿到的行必须仍是 READY 且未被删。</li>
     * </ul>
     */
    private List<MobileFileRef> normalized(List<MobileFileRef> refs, long userId) {
        // 保留首次出现顺序：fieldId 只影响展示/权限归类，文件本身只挂一次。
        Map<UUID, MobileFileRef> byFileId = new LinkedHashMap<>();
        for (MobileFileRef ref : refs == null ? List.<MobileFileRef>of() : refs) {
            if (ref == null || ref.fileId() == null || ref.fieldId() == null
                || ref.fieldId().isBlank() || ref.sortOrder() < 0) {
                throw new BizException("BAD_FILE_REF", "附件关联无效");
            }
            byFileId.putIfAbsent(ref.fileId(), ref);
        }
        if (byFileId.isEmpty()) return List.of();

        List<UUID> ordered = byFileId.keySet().stream().sorted().toList();
        Map<UUID, MobileFile> locked = fileMapper.selectByIdsForUpdate(ordered).stream()
            .collect(java.util.stream.Collectors.toMap(MobileFile::getId, java.util.function.Function.identity()));
        for (Map.Entry<UUID, MobileFileRef> entry : byFileId.entrySet()) {
            MobileFile file = locked.get(entry.getKey());
            if (file == null || file.getDeletedAt() != null || !"READY".equals(file.getStatus())) {
                throw new BizException("FILE_NOT_FOUND", "file not found");
            }
            if (!Objects.equals(file.getOwnerId(), userId)) {
                throw new AccessDeniedException("file belongs to another user");
            }
        }
        return new ArrayList<>(byFileId.values());
    }
}
