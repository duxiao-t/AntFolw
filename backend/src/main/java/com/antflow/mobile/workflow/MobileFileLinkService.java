package com.antflow.mobile.workflow;

import com.antflow.engine.BizException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    private List<MobileFileRef> normalized(List<MobileFileRef> refs, long userId) {
        List<MobileFileRef> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MobileFileRef ref : refs == null ? List.<MobileFileRef>of() : refs) {
            if (ref == null || ref.fileId() == null || ref.fieldId() == null
                || ref.fieldId().isBlank() || ref.sortOrder() < 0) {
                throw new BizException("BAD_FILE_REF", "附件关联无效");
            }
            MobileFile file = fileMapper.selectById(ref.fileId());
            if (file == null || file.getDeletedAt() != null || !"READY".equals(file.getStatus())) {
                throw new BizException("FILE_NOT_FOUND", "file not found");
            }
            if (!Objects.equals(file.getOwnerId(), userId)) {
                throw new AccessDeniedException("file belongs to another user");
            }
            if (seen.add(ref.fileId() + "\u0000" + ref.fieldId())) result.add(ref);
        }
        return result;
    }
}
