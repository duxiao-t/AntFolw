package com.antflow.form.importer;

import java.util.List;
import java.util.Map;

/**
 * 表单数据导入的对外 DTO。**字段名与顺序就是前端契约**（另一个 agent 已按这些名字对接），
 * 改名字等于直接改 API，必须同步改前端与 {@code frontend/config/antflow-api.json}。
 *
 * <p>三个容易混淆的数字：
 * <ul>
 *   <li>{@code rowCount}：文件里**非空**数据行的总数；</li>
 *   <li>{@code importableCount}：没有错误且不是重复的行数；</li>
 *   <li>{@code errorCount} / {@code skippedCount}：有问题的行数 / 重复行数。
 *       {@code importableCount + errorCount + skippedCount == rowCount}，
 *       行数按"行"计不按"问题"计（一行可能同时有 3 条 issue）。</li>
 * </ul>
 */
public final class ImportDtos {

    private ImportDtos() { }

    /**
     * 一列的对账结果。
     *
     * @param order     列在文件里的顺序（0 基），前端按它渲染
     * @param supported 该类型能否通过 Excel 回填（媒体字段为 false——文件没法从表格里带上来）
     */
    public record ColumnReport(String fieldId, String label, String type,
                               boolean required, boolean supported, int order) { }

    /**
     * 一条行级问题。
     *
     * @param row    **真实 Excel 行号**（1 基），不是数据行下标——空行被丢弃后下标会漂移
     * @param fieldId 定位不到具体字段时为 null（例如整行结构错误）
     */
    public record RowIssue(int row, String fieldId, String fieldLabel,
                           String code, String message) { }

    /** 预检结果：**不落库**，只回答"如果现在导入会发生什么"。 */
    public record ImportInspection(
        String templateVersion, long formId, String formCode, String formName, int formVersion,
        List<String> sheets, String sheetName,
        List<ColumnReport> columns, List<String> missingColumns,
        int rowCount, int importableCount, int errorCount, int skippedCount,
        List<RowIssue> issues, boolean issuesTruncated,
        List<Map<String, Object>> previews, List<String> warnings) { }

    /**
     * 导入结果。
     *
     * @param atomic 是否原子模式（true 表示"要么全成功、要么 0 行落库"）
     */
    public record ImportOutcome(
        long formId, String batchId, boolean atomic,
        int totalRows, int successCount, int failureCount, int skippedCount,
        List<Long> createdIds, List<RowIssue> failures, boolean failuresTruncated,
        List<String> warnings) { }
}
