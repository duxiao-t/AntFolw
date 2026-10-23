package com.antflow.form.importer;

import com.antflow.common.BusinessNumberService;
import com.antflow.form.FormDefinition;
import com.antflow.form.importer.FormImportParser.RowPlan;
import com.antflow.form.importer.ImportDtos.RowIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写库阶段：{@code business_no} 生成 + 分批 INSERT + 跳过模式的逐行降级。
 *
 * <p>**刻意不用 {@code @Transactional}**：跳过模式必须是无事务的，而类内自调用又会绕过
 * Spring 代理（注解看着生效、其实没有事务），比不用更危险。所以事务边界一律走
 * {@link TransactionTemplate}，且由本类自己持有。
 *
 * <p>两条与 PostgreSQL 有关的硬约束：
 * <ol>
 *   <li><b>跳过模式不能"一个事务里 try/catch 继续"</b>：一条语句报错后整个事务进入
 *       aborted 状态，后续语句一律 {@code current transaction is aborted}——catch 住了也写不进去。
 *       所以按 500 行一批独立提交；整批失败时**再逐行独立提交**定位坏行。</li>
 *   <li><b>幂等键用 import_key，不用 business_no</b>：见 {@code V62} 迁移注释。</li>
 * </ol>
 */
@Component
public class FormDataImportWriter {

    /** 跳过模式的批大小：批太大失败成本高，批太小事务开销大。 */
    private static final int SKIP_BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final BusinessNumberService businessNumbers;
    private final ObjectMapper json;

    public FormDataImportWriter(JdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                                BusinessNumberService businessNumbers, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.businessNumbers = businessNumbers;
        this.json = json;
    }

    /** 原子模式：任何一行失败都要整体回滚，异常原样抛给调用方翻成 422。 */
    void insertAtomically(FormDefinition form, Collection<RowPlan> rows, long userId,
                          Long companyId, String batchId) {
        transactionTemplate.executeWithoutResult(status ->
            insertRows(form, rows, userId, companyId, batchId));
    }

    /**
     * 跳过模式：每 500 行一个独立事务。
     *
     * <p>整批失败**必须**降级逐行重试：PostgreSQL 的 aborted 事务无法继续，整批回滚后
     * 我们只知道"这 500 行里有一行坏"，不知道是哪一行；逐行提交才能给出准确的行号。
     */
    List<RowIssue> insertInBatches(FormDefinition form, List<RowPlan> rows, long userId,
                                   Long companyId, String batchId) {
        List<RowIssue> failures = new ArrayList<>();
        for (int from = 0; from < rows.size(); from += SKIP_BATCH_SIZE) {
            List<RowPlan> chunk = rows.subList(from, Math.min(rows.size(), from + SKIP_BATCH_SIZE));
            try {
                transactionTemplate.executeWithoutResult(status ->
                    insertRows(form, chunk, userId, companyId, batchId));
            } catch (RuntimeException batchError) {
                for (RowPlan row : chunk) {
                    try {
                        transactionTemplate.executeWithoutResult(status ->
                            insertRows(form, List.of(row), userId, companyId, batchId));
                    } catch (RuntimeException rowError) {
                        failures.add(new RowIssue(row.row(), null, null, "IMPORT_VALIDATION_FAILED",
                            rootMessage(rowError)));
                    }
                }
            }
        }
        return failures;
    }

    private void insertRows(FormDefinition form, Collection<RowPlan> rows, long userId,
                            Long companyId, String batchId) {
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (RowPlan row : rows) {
            // business_no 由系统生成（沿用既有规则），**不会**用导入的「业务单号」覆盖它。
            String businessNo = businessNumbers.next(form, row.values());
            batch.add(new Object[] {
                form.getId(), form.getVersion(), businessNo, FormImportSupport.writeJson(json, row.values()), userId,
                companyId, row.importKey(), batchId
            });
        }
        jdbc.batchUpdate("""
            INSERT INTO t_form_data(
                form_def_id, form_def_version, business_no, data, status,
                created_by, company_id, company_inferred, import_key, import_batch)
            VALUES (?, ?, ?, ?::jsonb, 'SUBMITTED', ?, ?, false, ?, ?)
            """, batch);
    }

    List<Long> loadCreatedIds(String batchId) {
        // 批量插入拿不到 RETURNING；用批次号回查（import_batch 只用于回溯，不参与唯一性）。
        return jdbc.query("SELECT id FROM t_form_data WHERE import_batch = ? ORDER BY id",
            (rs, row) -> rs.getLong(1), batchId);
    }

    /** 供编排层把底层异常翻成 422 时取真正的原因（不要只报 jdbc 的外层包装）。 */
    static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
