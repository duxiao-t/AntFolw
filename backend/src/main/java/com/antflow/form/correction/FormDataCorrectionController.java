package com.antflow.form.correction;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 表单数据「事后修正」接口。
 *
 * <p>为什么单开一个 controller 而不是继续往 {@code FormDataController} 里加：
 * 那边已经是提交/列表/导出/详情混在一起，再加 4 个端点会到 200 行以上；
 * 修正是一块独立的、风险更高的能力，单独成类更便于审计与权限审阅。
 *
 * <p>用 {@code POST + 子资源}而不是 {@code PUT /{id}}：修正不是幂等替换，
 * 它是"产生一次带原因的变更事件"。重复提交必须得到两条记录或一条冲突，
 * 不能被静默吞成覆盖——那会让审计丢失中间过程。
 */
@RestController
@RequestMapping("/api/forms/data")
@RequiredArgsConstructor
public class FormDataCorrectionController {

    private final FormDataCorrectionService service;

    /** 打开修正弹窗：返回单据自己版本的 schema、当前值、可改字段与并发基准。 */
    @GetMapping("/{id}/correction-form")
    @PreAuthorize("@authz.consoleEntry()")
    public CorrectionDtos.CorrectionFormView correctionForm(@PathVariable Long id) {
        return service.view(id);
    }

    /** 提交一次修正；表单开启复核时返回 PENDING_REVIEW 且数据未变。 */
    @PostMapping("/{id}/corrections")
    @PreAuthorize("@authz.consoleEntry()")
    public CorrectionDtos.CorrectionResult correct(@PathVariable Long id,
                                                    @RequestBody CorrectionDtos.CorrectionRequest body) {
        return service.correct(id, body);
    }

    /** 修正历史（含待复核与被驳回的记录）。 */
    @GetMapping("/{id}/corrections")
    @PreAuthorize("@authz.consoleEntry()")
    public List<CorrectionDtos.CorrectionView> history(@PathVariable Long id,
                                                       @RequestParam(defaultValue = "50") int limit) {
        return service.history(id, limit);
    }

    /** 复核一条待复核的修正。 */
    @PostMapping("/{id}/corrections/{correctionId}/review")
    @PreAuthorize("@authz.consoleEntry()")
    public CorrectionDtos.CorrectionResult review(@PathVariable Long id,
                                                   @PathVariable Long correctionId,
                                                   @RequestBody CorrectionDtos.ReviewRequest body) {
        return service.review(id, correctionId, body);
    }
}
