package com.antflow.form.importer;

import com.antflow.authz.AuthorizationService;
import com.antflow.common.BusinessNumberService;
import com.antflow.engine.BizException;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.importer.FormImportParser.Plan;
import com.antflow.form.importer.ImportDtos.ImportInspection;
import com.antflow.form.importer.ImportDtos.ImportOutcome;
import com.antflow.form.importer.ImportDtos.RowIssue;
import com.antflow.org.CompanyAttribution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 表单数据**历史补录**的编排：直接写 {@code status='SUBMITTED'} 的台账行。
 *
 * <p>不建流程实例、不建待办、不写 revision——这是产品已拍板的口径：补的是"系统上线前已经发生"
 * 的单据，不存在审批过程。因此提交人就是**导入人**（{@code created_by}），
 * 公司归属按 {@link CompanyAttribution} 解析（与无流程提交同一套规则）。
 *
 * <p>本类只留两条编排（{@code inspect} / {@code importData}）与"结果怎么报"：
 * <ul>
 *   <li>解析 + 校验在 {@link FormImportParser}（只读，预检只需要它）；</li>
 *   <li>写库 + 事务边界在 {@link FormDataImportWriter}。</li>
 * </ul>
 *
 * <p>**原子模式必须"先全部校验、再统一写"**：任何一行失败就 0 行落库。
 * 事务边界刻意走 {@link TransactionTemplate} 而不是 {@code @Transactional}——
 * {@link #importData} 在 {@code skipErrorRows=true} 时必须是**无事务**的，
 * 类内自调用又会绕过 Spring 代理（注解看着生效、其实没有事务），比不用更危险。
 */
@Service
public class FormDataImportService {

    private final FormImportParser parser;
    private final FormDataImportWriter writer;
    private final AuthorizationService authorizationService;

    @Autowired
    public FormDataImportService(FormImportParser parser, FormDataImportWriter writer,
                                 AuthorizationService authorizationService) {
        this.parser = parser;
        this.writer = writer;
        this.authorizationService = authorizationService;
    }

    /**
     * 兼容构造器：让不启动 Spring 的单元测试一次装配好两组协作者。
     *
     * <p>参数与拆分前完全一致，装配方式也只是把原来在 {@code parse}/{@code insertRows} 里
     * 的职责交给对应新类，**不改变**任何对外行为。
     */
    FormDataImportService(FormDefinitionService formDefinitionService, FormImportValueCodec codec,
                          ObjectMapper json, JdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                          BusinessNumberService businessNumbers, CompanyAttribution companyAttribution,
                          AuthorizationService authorizationService) {
        this(new FormImportParser(formDefinitionService, codec,
                new FormImportTemplateReader(formDefinitionService, json),
                new FormImportKeyLookup(jdbc), json, companyAttribution),
            new FormDataImportWriter(jdbc, transactionTemplate, businessNumbers, json),
            authorizationService);
    }

    // ---------------------------------------------------------------- 预检

    /** 只读预检：解析 + 校验，**不落库**。 */
    public ImportInspection inspect(long formId, MultipartFile file, String sheetName) {
        Plan plan = parser.parse(formId, file, sheetName, null, true);
        return new ImportInspection(String.valueOf(FormImportTemplateService.TEMPLATE_VERSION),
            plan.form().getId(), plan.form().getCode(), plan.form().getName(),
            plan.form().getVersion() == null ? 0 : plan.form().getVersion(),
            plan.sheets(), plan.sheetName(),
            plan.columnReports(), plan.missingColumns(),
            plan.rowCount(), plan.importableCount(), plan.errorCount(), plan.skippedCount(),
            plan.issues(), plan.issuesTruncated(), plan.previews(), plan.warnings());
    }

    // ---------------------------------------------------------------- 导入

    /**
     * @param skipErrorRows false：原子模式，任何一行不合格 → 422 且 0 行落库
     *                      true ：跳过模式，逐批提交，坏行单独回报
     * @param companyId     仅"无部门的全局管理员"需要显式指定；其他人按身份推导
     */
    public ImportOutcome importData(long formId, MultipartFile file, String sheetName,
                                    boolean skipErrorRows, Long companyId) {
        Plan plan = parser.parse(formId, file, sheetName, companyId, false);
        long userId = authorizationService.currentUserId();
        String batchId = nextBatchId();

        List<FormImportParser.RowPlan> insertable = plan.rows().stream()
            .filter(row -> row.issues().isEmpty() && !row.skipped()).toList();

        if (plan.errorCount() > 0 && !skipErrorRows) {
            throw new BizException("IMPORT_VALIDATION_FAILED", atomicFailureMessage(plan));
        }
        if (insertable.isEmpty()) {
            if (plan.skippedCount() > 0 && plan.errorCount() == 0) {
                throw new BizException("IMPORT_DUPLICATE",
                    "文件中的 " + plan.skippedCount() + " 行都已经导入过，没有新增数据");
            }
            if (plan.errorCount() > 0) {
                return outcome(plan, batchId, !skipErrorRows, List.of(), validationFailures(plan),
                    0, plan.errorCount());
            }
            throw new BizException("IMPORT_EMPTY", "没有可导入的数据行");
        }

        List<RowIssue> insertFailures = new ArrayList<>();
        if (!skipErrorRows) {
            try {
                writer.insertAtomically(plan.form(), insertable, userId, plan.companyId(), batchId);
            } catch (BizException error) {
                throw error;
            } catch (RuntimeException error) {
                // 事务已回滚；这里只负责把底层异常翻成 422，绝不吞掉。
                throw new BizException("IMPORT_VALIDATION_FAILED",
                    "导入失败，未写入任何数据：" + FormDataImportWriter.rootMessage(error));
            }
        } else {
            insertFailures = writer.insertInBatches(plan.form(), insertable, userId,
                plan.companyId(), batchId);
        }

        List<Long> createdIds = writer.loadCreatedIds(batchId);
        List<RowIssue> failures = new ArrayList<>(validationFailures(plan));
        failures.addAll(insertFailures);
        return outcome(plan, batchId, !skipErrorRows, createdIds, failures,
            Math.max(0, insertable.size() - insertFailures.size()),
            plan.errorCount() + insertFailures.size());
    }

    // ---------------------------------------------------------------- 结果

    private static String nextBatchId() {
        return "IMP-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String atomicFailureMessage(Plan plan) {
        List<RowIssue> first = plan.issues().stream().limit(5).toList();
        StringBuilder message = new StringBuilder("共 ")
            .append(plan.errorCount()).append(" 行校验未通过，已取消导入（0 行落库）：");
        for (RowIssue issue : first) {
            message.append(" 第").append(issue.row()).append("行 ")
                .append(issue.message()).append("；");
        }
        if (plan.errorCount() > first.size()) message.append("…");
        return message.toString();
    }

    private static ImportOutcome outcome(Plan plan, String batchId, boolean atomic,
                                         List<Long> createdIds, List<RowIssue> failures,
                                         int successCount, int failureCount) {
        List<RowIssue> capped = failures.size() > FormImportParser.MAX_ISSUES
            ? failures.subList(0, FormImportParser.MAX_ISSUES) : failures;
        return new ImportOutcome(plan.form().getId(), batchId, atomic,
            plan.rowCount(), successCount, failureCount, plan.skippedCount(),
            createdIds, capped, failures.size() > FormImportParser.MAX_ISSUES, plan.warnings());
    }

    private static List<RowIssue> validationFailures(Plan plan) {
        List<RowIssue> failures = new ArrayList<>();
        for (FormImportParser.RowPlan row : plan.rows()) {
            failures.addAll(row.issues());
        }
        return failures;
    }
}
