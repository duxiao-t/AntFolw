package com.antflow.process;

/**
 * 表单数据修订的原因码（对应 {@code t_form_data_revision.reason}）。
 *
 * <p>集中声明是为了避免同义字符串散落在引擎各处：这些值是**审计口径**，
 * 一旦出现 {@code "RESUBMIT"} 与 {@code "RESUBMITTED"} 这种笔误，
 * 历史追溯就会漏掉一半的修订。新增原因应当同时改这里与数据库端的约束。
 *
 * <p>取值语义（与 {@code DefinitionVersionRepository#createRevision} 的写入时机一一对应）：
 * <ul>
 *   <li>{@link #INITIAL_SUBMIT} — 单据首次正式提交时建立的基线修订</li>
 *   <li>{@link #NODE_EDIT} — 审批节点上按 formPerms 的"可编辑"字段回写</li>
 *   <li>{@link #RESUBMIT} — 被驳回后申请人原单重提</li>
 *   <li>{@link #REJECTED} — 驳回瞬间留存的快照（status 为 NEEDS_REVISION）</li>
 *   <li>{@link #ADMIN_CORRECTION} — 审批终态之后的事后修正</li>
 *   <li>{@link #CORRECTION_ROLLBACK} — 把某次修正回滚掉（回滚本身也是一次修正）</li>
 * </ul>
 *
 * <p>注意：这里只有 reason，不含修订的 status（DRAFT/SUBMITTED/NEEDS_REVISION），
 * 后者由调用方按业务语义传入，两者不要混为一谈。
 */
public final class RevisionReasons {

    public static final String INITIAL_SUBMIT = "INITIAL_SUBMIT";
    public static final String NODE_EDIT = "NODE_EDIT";
    public static final String RESUBMIT = "RESUBMIT";
    public static final String REJECTED = "REJECTED";
    public static final String ADMIN_CORRECTION = "ADMIN_CORRECTION";
    public static final String CORRECTION_ROLLBACK = "CORRECTION_ROLLBACK";

    private RevisionReasons() {
    }
}
