/**
 * 企业微信集成：通讯录同步（部门/成员/上级/负责人）、应用配置、免登与消息。
 *
 * <h2>这个包负责什么</h2>
 * <ul>
 *   <li>{@link com.antflow.integration.wecom.WecomService} —— 同步编排入口：抢任务、拉通讯录、
 *       驱动下面几个协作者、收尾。对外 API 也在这里（Controller 只依赖它）。</li>
 *   <li>{@link com.antflow.integration.wecom.WecomConfigService} —— 连接配置与同步计划。</li>
 *   <li>{@link com.antflow.integration.wecom.WecomAppService} —— 应用侧门面（应用状态、接入信息、JS-SDK 签名、
 *       素材导入、投递运维）；免登在 {@code WecomAppLoginService}，消息入队与投递在
 *       {@code WecomAppMessageDispatcher}，token/ticket 缓存在 {@code WecomAppTokenCache}，
 *       配置读取在 {@code WecomAppConfigStore}。</li>
 *   <li>{@link com.antflow.integration.wecom.WecomJobStore} —— {@code t_wecom_sync_job} 的唯一归属。</li>
 *   <li>{@link com.antflow.integration.wecom.WecomSyncLease} —— 租约：谁抢到任务谁才能写。</li>
 *   <li>{@link com.antflow.integration.wecom.WecomMappingRules} —— 纯函数：字段映射、判定、清洗。</li>
 *   <li>{@link com.antflow.integration.wecom.WecomDirectorySync} / {@code WecomDepartmentSync} /
 *       {@code WecomManagerSync} —— 三类落库：成员、部门树、直属上级。</li>
 *   <li>{@link com.antflow.integration.wecom.WecomSyncErrors} —— 底层异常 → 用户可读文案。</li>
 * </ul>
 *
 * <h2>这个包不负责什么</h2>
 * <ul>
 *   <li>**不做权限模型**：只管"这个用户能不能碰这家公司"（{@code WecomAccessGuard}），
 *       角色与能力码的判定在 {@code com.antflow.authz}。</li>
 *   <li>**不直接对外暴露企业微信 API 细节**：HTTP 调用与加解密在 {@code WecomClient} / {@code WecomSecretCipher}。</li>
 *   <li>**不维护人员主数据**：只写 {@code t_user}/{@code t_department} 的同步相关字段，
 *       组织架构的其它维护路径在 {@code com.antflow.org}。</li>
 * </ul>
 *
 * <h2>依赖方向（不要反向）</h2>
 * <pre>
 * Controller → WecomService → { ConfigService, JobStore, DirectorySync, DepartmentSync, ManagerSync }
 *                              ↘ 以上都可以用 WecomSyncLease / WecomMappingRules / WecomSyncErrors
 * </pre>
 * <p>{@code WecomMappingRules} 与 {@code WecomSyncErrors} 是**无状态工具**，不注入任何 bean；
 * 往里面加 {@code JdbcTemplate} 就说明职责放错了。
 *
 * <h2>改这里之前先读</h2>
 * <p>{@code docs/class-decomposition-execution-2026-10-20.md} §3.2：这个包原来是一个 1313 行的
 * 单类，六刀拆成现在的结构；每刀都保持"公开签名与 SQL 不变"，改动必须继续遵守这条。
 */
package com.antflow.integration.wecom;
