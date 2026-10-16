package com.antflow;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构边界门禁：**只拦新增违规**。
 *
 * <p>这套规则表达的是"应该是什么样"，不是"现在是什么样"。当前存量违规用
 * {@link FreezingArchRule} 冻在 {@code backend/archunit_store/}（随仓库提交）：今天这些违规照旧，
 * 但**任何人再加一条同类违规，测试当场红**。存量清理另行安排，不在门禁的范围内。
 *
 * <p>两条纪律：
 * <ol>
 *   <li>**不要改规则的措辞**。冻结是按规则描述做的键，改一个字就等于把基线清空、重新冻结一遍
 *       （新违规会被静默吞掉）。要改语义就同时删掉旧的存盘条目并重新冻结。</li>
 *   <li>规则只查**生产代码**（{@code DO_NOT_INCLUDE_TESTS}）——测试里为了造场景引用别的包是正常的。</li>
 * </ol>
 */
class ArchitectureBoundaryTest {

    /**
     * 冻结存盘的创建要**显式开关**：只有本地首次建立基线时才允许，CI 永远不开。
     * 否则 CI 上少了一份存盘、或规则措辞被改动，新违规会被静默地重新冻结一遍——门禁看着是绿的，其实失效了。
     *
     * <p>建立/重建基线（在 {@code backend/} 下）：
     * <pre>mvn -B test -Dtest=ArchitectureBoundaryTest -Dantflow.arch.freeze=true</pre>
     */
    @BeforeAll static void allowStoreCreationOnlyForBootstrap() {
        if (Boolean.getBoolean("antflow.arch.freeze")) {
            ArchConfiguration.get().setProperty("freeze.store.default.allowStoreCreation", "true");
        }
    }

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.antflow");

    /**
     * 依赖方向单向：适配层（mobile，手机端的 BFF）可以依赖领域，领域不能反向依赖它。
     * 冻结时存量 7 处（auth/backup/form/wecom/org 都在用 mobile 的 file/draft/org 类型）。
     */
    @Test void domainDoesNotDependOnTheMobileAdapter() {
        ArchRule rule = noClasses()
            .that().resideInAnyPackage("..engine..", "..form..", "..process..", "..task..",
                "..org..", "..authz..", "..audit..", "..automation..", "..notify..",
                "..report..", "..navigation..", "..workplace..", "..backup..", "..common..")
            .should().dependOnClassesThat().resideInAPackage("..mobile..")
            .because("领域不该反向依赖手机端适配层；真要共用 file/draft/org 能力，应把它们上移到中立包");
        FreezingArchRule.freeze(rule).check(CLASSES);
    }

    /** `com.antflow.dev` 只给本地调试用，生产代码不该引用。 */
    @Test void nothingDependsOnDevOnlyCode() {
        ArchRule rule = noClasses()
            .that().resideOutsideOfPackage("..dev..")
            .should().dependOnClassesThat().resideInAPackage("..dev..")
            .because("com.antflow.dev 是本地调试用的，生产路径引用它等于把调试代码带上线");
        FreezingArchRule.freeze(rule).check(CLASSES);
    }

    /** 审计是记录者：不该反向依赖业务领域（否则业务一改，审计口径就跟着漂）。 */
    @Test void auditDoesNotDependOnBusinessModules() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..audit..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..engine..", "..form..", "..process..", "..task..")
            .because("审计只做追加记录，反向依赖业务模块会让它跟业务耦合在一起");
        FreezingArchRule.freeze(rule).check(CLASSES);
    }
}
