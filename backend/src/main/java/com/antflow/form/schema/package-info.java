/**
 * 表单 schema 的纯逻辑：解析、发布期结构校验、提交期值校验、可见性求值与首填投影/规范化。
 *
 * <h2>这个包负责什么</h2>
 * <ul>
 *   <li>{@link com.antflow.form.schema.FormSchemaParser} —— schema 文本 → 节点树；
 *       结构契约（字段类型、children 形状）与叶子字段清单。</li>
 *   <li>{@link com.antflow.form.schema.FormSchemaValidator} —— 发布期校验：选项、展示样式、
 *       清单结果色、{@code displayCondition} 环。</li>
 *   <li>{@link com.antflow.form.schema.FormSubmissionValidator} —— 提交期校验：树遍历与递归骨架
 *       （必填、选项值、明细行、矩阵）。</li>
 *   <li>{@link com.antflow.form.schema.FormFieldValueValidator} —— 叶子字段的值校验：形状、
 *       长度/精度、清单、用户选择器、部门选择器（不走树）。</li>
 *   <li>{@link com.antflow.form.schema.FormMatrixRules} —— {@code matrix_fill} 的行列轴与单元格
 *       （发布态与提交态共用）。</li>
 *   <li>{@link com.antflow.form.schema.FormVisibility} —— {@code displayCondition} 的运行时求值。</li>
 *   <li>{@link com.antflow.form.schema.FormStarterProjector} —— 面向发起人的投影（删 HIDDEN 字段）。</li>
 *   <li>{@link com.antflow.form.schema.FormStarterCanonicalizer} —— ROOT 字段权限落地
 *       （首填/返工的值合并与再校验）。</li>
 *   <li>{@link com.antflow.form.schema.FormSchemaJson} —— schema/settings 的 JSON 边界
 *       （jsonb 排版无关的比较）。</li>
 * </ul>
 *
 * <h2>这个包不负责什么</h2>
 * <ul>
 *   <li>**不碰库、不碰事务**：这里的类都不注入 {@code JdbcTemplate}/{@code Mapper}，
 *       也不加 {@code @Transactional}。要读表单定义、要落草稿，回
 *       {@code FormDefinitionService}。</li>
 *   <li>**不做权限判定**：ROOT 字段权限（{@code formPerms}）只是 schema 里的配置，
 *       谁能维护表单在 {@code com.antflow.authz}。</li>
 *   <li>**不解析选项数据源的行**：外部选项的绑定合法性由 {@code form.options.OptionRuntimeService}
 *       判定，这里只决定"什么时候该问它"。</li>
 * </ul>
 *
 * <h2>依赖方向（不要反向）</h2>
 * <pre>
 * FormDefinitionService / 各 Controller
 *        ↓
 * FormStarterCanonicalizer → FormStarterProjector → FormSchemaParser → FormMatrixRules
 *        ↓                        ↓                      ↑
 * FormSubmissionValidator → FormVisibility          FormSchemaValidator
 *        ├→ FormFieldValueValidator（叶子值校验，只借 FormVisibility.isEmpty）
 *        ↓
 * FormSchemaJson（唯一被所有类共享的 JSON 边界）
 * </pre>
 * <p>上图的箭头只朝下：本包任何类都**不得** import {@code FormDefinitionService}，
 * 也不得反向调用某个上层协作者——需要编排时由 {@code FormDefinitionService} 编。
 *
 * <h2>改这里之前先读</h2>
 * <p>{@code docs/class-decomposition-form-definition-2026-10-22.md}：这个包是 1403 行的
 * {@code FormDefinitionService} 拆出来的；搬家时方法与注释逐字保留，改动必须继续遵守
 * "公开签名与错误码不变"。
 */
package com.antflow.form.schema;
