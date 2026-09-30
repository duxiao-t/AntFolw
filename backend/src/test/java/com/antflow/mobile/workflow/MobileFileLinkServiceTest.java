package com.antflow.mobile.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import com.antflow.engine.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

/**
 * 提交表单时"关联附件"的校验与去重。
 *
 * <p>两个坑都是真会炸的：主键是 `(form_data_id, file_id)`，而上传端的 SHA 去重会把两张不同字段的
 * 同一张照片收敛成同一个 id——按 `fileId+fieldId` 去重就会两次插入同一主键，整单 500。
 */
class MobileFileLinkServiceTest {
    private MobileWorkflowMapper workflowMapper;
    private MobileFileMapper fileMapper;
    private MobileFileLinkService service;

    @BeforeEach
    void setUp() {
        workflowMapper = Mockito.mock(MobileWorkflowMapper.class);
        fileMapper = Mockito.mock(MobileFileMapper.class);
        service = new MobileFileLinkService(workflowMapper, fileMapper);
    }

    @Test
    void sameFileInTwoFieldsIsLinkedOnce() {
        UUID fileId = UUID.randomUUID();
        Mockito.when(fileMapper.selectByIdsForUpdate(any())).thenReturn(List.of(ready(fileId, 7L)));

        service.append(100L, List.of(
            new MobileFileRef(fileId, "fieldA", 0),
            new MobileFileRef(fileId, "fieldB", 1)), 7L);

        ArgumentCaptor<String> fieldId = ArgumentCaptor.forClass(String.class);
        Mockito.verify(workflowMapper, Mockito.times(1))
            .insertFileLink(anyLong(), any(), fieldId.capture(), anyInt());
        // 首次出现的字段胜出（值 JSON 里两个字段都还在，展示不受影响）。
        assertThat(fieldId.getValue()).isEqualTo("fieldA");
    }

    /** 锁按 id 升序批量取一次：乱序传入也不会与并发 delete 形成 ABBA 死锁。 */
    @Test
    void locksAllFilesOnceInAscendingIdOrder() {
        UUID smaller = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID bigger = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Mockito.when(fileMapper.selectByIdsForUpdate(any()))
            .thenReturn(List.of(ready(smaller, 7L), ready(bigger, 7L)));

        service.append(100L, List.of(
            new MobileFileRef(bigger, "fieldB", 0),
            new MobileFileRef(smaller, "fieldA", 1)), 7L);

        ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.forClass(List.class);
        Mockito.verify(fileMapper, Mockito.times(1)).selectByIdsForUpdate(ids.capture());
        assertThat(ids.getValue()).containsExactly(smaller, bigger);
    }

    @Test
    void rejectsMissingProcessingOrForeignFiles() {
        UUID deleted = UUID.randomUUID();
        UUID processing = UUID.randomUUID();
        UUID mine = UUID.randomUUID();
        MobileFile deletedRow = ready(deleted, 7L);
        deletedRow.setDeletedAt(java.time.OffsetDateTime.now());
        MobileFile processingRow = ready(processing, 7L);
        processingRow.setStatus("PROCESSING");
        Mockito.when(fileMapper.selectByIdsForUpdate(any()))
            .thenReturn(List.of(deletedRow, processingRow, ready(mine, 8L)));

        assertThatThrownBy(() -> service.append(100L, List.of(new MobileFileRef(deleted, "f", 0)), 7L))
            .isInstanceOf(BizException.class).hasMessageContaining("file not found");
        assertThatThrownBy(() -> service.append(100L,
            List.of(new MobileFileRef(processing, "f", 0)), 7L))
            .isInstanceOf(BizException.class).hasMessageContaining("file not found");
        // 别人的文件：明确 403，不是"文件不存在"
        assertThatThrownBy(() -> service.append(100L, List.of(new MobileFileRef(mine, "f", 0)), 7L))
            .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * 同一个文件先以"受限字段"存在、本次又以"可编辑字段"提交：两次插入落到同一个主键上，
     * 依赖 `ON CONFLICT DO UPDATE` 让**后插的可编辑字段**成为权威（DO NOTHING 会把这次迁移吞掉）。
     */
    @Test
    void reconcileEditableInsertsTheEditableFieldLast() {
        UUID fileId = UUID.randomUUID();
        java.util.List<MobileWorkflowMapper.FormDataFileLink> restricted = new ArrayList<>();
        restricted.add(link(fileId, "readonlyField", 0));
        Mockito.when(workflowMapper.selectFileLinks(100L)).thenReturn(restricted);
        Mockito.when(fileMapper.selectByIdsForUpdate(any())).thenReturn(List.of(ready(fileId, 7L)));

        service.reconcileEditable(100L, List.of(new MobileFileRef(fileId, "editableField", 0)), 7L,
            java.util.Map.of("readonlyField", "READONLY", "editableField", "EDITABLE"));

        java.util.List<String> insertedFields = new ArrayList<>();
        ArgumentCaptor<String> fields = ArgumentCaptor.forClass(String.class);
        Mockito.verify(workflowMapper, Mockito.times(2)).insertFileLink(anyLong(),
            Mockito.eq(fileId), fields.capture(), anyInt());
        insertedFields.addAll(fields.getAllValues());
        Mockito.verify(workflowMapper).deleteFileLinks(100L);
        assertThat(insertedFields).containsExactly("readonlyField", "editableField");
    }

    private static MobileWorkflowMapper.FormDataFileLink link(UUID fileId, String fieldId, int order) {
        return new MobileWorkflowMapper.FormDataFileLink(fileId, fieldId, order);
    }

    private static MobileFile ready(UUID id, long ownerId) {
        MobileFile file = new MobileFile();
        file.setId(id);
        file.setOwnerId(ownerId);
        file.setStatus("READY");
        file.setContentType("image/png");
        file.setStorageKey("image/" + id);
        return file;
    }
}
