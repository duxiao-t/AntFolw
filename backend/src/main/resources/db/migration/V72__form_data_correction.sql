-- 表单数据「事后修正」台账。
--
-- 背景：系统此前只有"填报制单"一条路，审批完成后发现录错（金额、错别字、附件）
-- 只能直接改库。修订表 t_form_data_revision 与追加原语 createRevision 其实早就存在，
-- 被 INITIAL_SUBMIT / NODE_EDIT / RESUBMIT / REJECTED 四个场景用着，唯独缺"完成后修正"
-- 这一类，所以缺的不是版本能力，而是业务入口、权限与审计口径。
--
-- 这张表只回答"谁、何时、为什么、改了哪些字段、能不能回滚"；
-- **数据副本不在这里**——由 t_form_data_revision 承担，避免同一份 JSON 存两遍后不一致。
--
-- 双人复核：表单级开关走 t_form_definition.settings -> correction.reviewRequired（默认关），
-- 刻意不给 t_form_definition 加列：settings 已有 businessNumber 这种命名空间先例，
-- 加列会让"表单配置"分裂成两处。开关开启时，修正先落 PENDING_REVIEW（数据不动），
-- 由另一个人复核通过后才真正写数据，所以 revision_id 允许为空。
--
-- 不加 corrected_at / correction_count 这类冗余列到 t_form_data：
-- "是否被修正过"用 EXISTS 派生即可，冗余列会引入"与台账不一致"这个新的不变量。

CREATE TABLE t_form_data_correction (
    id                  BIGSERIAL PRIMARY KEY,
    form_data_id        BIGINT NOT NULL REFERENCES t_form_data(id),
    -- APPLIED：已直接生效；PENDING_REVIEW：等复核（数据尚未改）；REJECTED：复核驳回
    status              VARCHAR(16) NOT NULL DEFAULT 'APPLIED',
    -- 生效后指向本次修正产生的修订；待复核/被驳回时为空
    revision_id         BIGINT REFERENCES t_form_data_revision(id),
    -- 修正前那一刻的修订（导入行没有修订，故可空）；回滚与并发排查都靠它
    from_revision_id    BIGINT REFERENCES t_form_data_revision(id),
    instance_id         BIGINT REFERENCES t_process_instance(id),
    actor_user_id       BIGINT NOT NULL REFERENCES t_user(id),
    reason_code         VARCHAR(32) NOT NULL,
    reason_text         VARCHAR(500),
    -- {fieldId: {before, after}}：只记被改的字段，便于 diff 展示与审计检索
    field_changes       JSONB NOT NULL DEFAULT '{}'::jsonb,
    -- 待复核时保存"拟修正后的完整数据"。不能省：复核通过时要按提交那一刻的意图落库，
    -- 期间主表可能已被别的路径（重提、另一次修正）改过，重新合并会得到不同结果。
    proposed_data       JSONB,
    attachments_changed BOOLEAN NOT NULL DEFAULT false,
    -- 留证据，不是改它：修正永远不写 t_form_data.company_id / company_inferred
    company_id_at_time  BIGINT REFERENCES t_company(id),
    instance_status     VARCHAR(16),
    reviewed_by         BIGINT REFERENCES t_user(id),
    reviewed_at         TIMESTAMPTZ,
    review_note         VARCHAR(500),
    -- 回滚本身也是一次修正，指回被回滚的那条
    rollback_of         BIGINT REFERENCES t_form_data_correction(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_correction_status CHECK (
        status IN ('APPLIED', 'PENDING_REVIEW', 'REJECTED')),
    -- 原因集合刻意写成 CHECK：原因口径属于审计语义，新增原因应当走一次迁移，
    -- 而不是被随手塞一个新的字符串进来。
    CONSTRAINT ck_correction_reason CHECK (reason_code IN (
        'AMOUNT_ERROR', 'TYPO', 'WRONG_ATTACHMENT', 'MISSING_VALUE', 'OTHER')),
    -- 已生效却没有对应修订，说明写序出错了（必须先 createRevision 再落台账）
    CONSTRAINT ck_correction_applied_has_revision CHECK (
        status <> 'APPLIED' OR revision_id IS NOT NULL)
);

-- 台账按单据倒序翻页
CREATE INDEX ix_correction_data ON t_form_data_correction (form_data_id, id DESC);
-- 按人排查（"这个人最近改了哪些单"）
CREATE INDEX ix_correction_actor ON t_form_data_correction (actor_user_id, created_at DESC);
-- 待复核队列：只索引待复核行，量小且查询高频
CREATE INDEX ix_correction_pending ON t_form_data_correction (created_at)
    WHERE status = 'PENDING_REVIEW';

COMMENT ON TABLE t_form_data_correction IS
    '表单数据事后修正台账：只存事件元信息，数据副本在 t_form_data_revision';
COMMENT ON COLUMN t_form_data_correction.proposed_data IS
    '待复核时的拟修正结果；APPLIED/REJECTED 为空';
