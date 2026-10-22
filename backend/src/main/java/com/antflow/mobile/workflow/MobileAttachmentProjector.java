package com.antflow.mobile.workflow;

import com.antflow.attachment.FormDataFileMapper;
import com.antflow.attachment.MobileFile;
import com.antflow.attachment.MobileFileDto;
import com.antflow.attachment.MobileFileRef;
import com.antflow.form.FormDefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 移动端的附件投影：把表单数据上的文件链接转换成前端可直接渲染的 {@link MobileFileDto}。
 *
 * <p>两个视图不能合并：
 * <ul>
 *   <li>{@link #files} 是审批人视图——按表单数据取全部附件；</li>
 *   <li>{@link #starterFiles} 是发起人视图——必须剔除 {@code HIDDEN} 字段上的附件。
 *       这是权限可见性，不是展示偏好；合并或"顺手复用"会让发起人在详情页看到被隐藏字段的附件。</li>
 * </ul>
 *
 * <p>{@code starterEditableFiles} 是发起链路的对称规则：只有 {@code EDITABLE}（或未配置）的
 * 字段才允许把新附件落到表单数据上，其余字段即便前端传了也不落库。
 */
final class MobileAttachmentProjector {
    private final FormDataFileMapper fileLinks;
    private final FormDefinitionService formDefinitionService;

    MobileAttachmentProjector(FormDataFileMapper fileLinks,
                              FormDefinitionService formDefinitionService) {
        this.fileLinks = fileLinks;
        this.formDefinitionService = formDefinitionService;
    }

    List<MobileFileDto> files(Long formDataId) {
        return fileLinks.selectFilesByFormDataId(formDataId).stream()
            .map(MobileAttachmentProjector::toFileDto)
            .toList();
    }

    List<MobileFileDto> starterFiles(Long formDataId, JsonNode processSnapshot) {
        Map<String, String> modes = formDefinitionService.starterFieldModes(processSnapshot);
        Set<UUID> visibleFileIds = new LinkedHashSet<>();
        for (FormDataFileMapper.FormDataFileLink link
                : fileLinks.selectFileLinks(formDataId)) {
            if (!"HIDDEN".equals(modes.get(link.fieldId()))) {
                visibleFileIds.add(link.fileId());
            }
        }
        return fileLinks.selectFilesByFormDataId(formDataId).stream()
            .filter(file -> visibleFileIds.contains(file.getId()))
            .map(MobileAttachmentProjector::toFileDto)
            .toList();
    }

    static List<MobileFileRef> filesOf(StartMobileInstanceRequest request) {
        return request.files() == null ? List.of() : request.files();
    }

    static List<MobileFileRef> starterEditableFiles(
        List<MobileFileRef> files, Map<String, String> modes) {
        return files.stream()
            .filter(file -> "EDITABLE".equals(modes.getOrDefault(file.fieldId(), "EDITABLE")))
            .toList();
    }

    static MobileFileDto toFileDto(MobileFile file) {
        return new MobileFileDto(
            file.getId(),
            file.getOriginalName(),
            file.getContentType(),
            file.getSizeBytes(),
            "/api/mobile/files/" + file.getId() + "/content"
        );
    }
}
