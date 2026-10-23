-- P4a：公司归属快照。
--
-- 背景：公司归属政策已拍板（模板全局共享、**归属在创建时快照**、公司管理员只收敛数据范围）。
-- 本迁移只锁"归属"，**不改任何读权限**——公司隔离是后续 P4 的事。
--
-- 为什么必须现在做：公司能从部门现推，而**部门可以改归属**。今天写快照是事实，
-- 晚一天写就多一天只能靠推断的数据。（这与 `started_dept_id` 当初做成快照是同一个理由。）
--
-- 三档含义（**别误读**）：
--   | 状态             | company_id | company_inferred |
--   | 新写入的确定快照  | 非空        | false            |
--   | 历史推断归属      | 非空        | true             |
--   | 历史待确认        | NULL       | false            |
-- **`NULL` 行的 `false` 不代表"已确认"**。将来的隔离判断不能只看 `company_id` 非空就当作事实，
-- 必须一并看 `company_inferred`。
--
-- 历史回填**不使用发起人今天的部门**——那正是口径里禁止的"猜"。只按该行自己的
-- `started_dept_id`（一个同样由推断得来、但至少是当时快照的列）去映射。

ALTER TABLE t_process_instance ADD COLUMN IF NOT EXISTS company_id BIGINT REFERENCES t_company(id);
ALTER TABLE t_process_instance ADD COLUMN IF NOT EXISTS company_inferred BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE t_form_data ADD COLUMN IF NOT EXISTS company_id BIGINT REFERENCES t_company(id);
ALTER TABLE t_form_data ADD COLUMN IF NOT EXISTS company_inferred BOOLEAN NOT NULL DEFAULT false;

-- 回填前先查重：`V1__init.sql` 里 `form_data_id` 只有 NOT NULL 外键、**没有 UNIQUE**，
-- "一个实例一份数据"是应用约定、不是数据库保证。若真有一份数据挂多个实例，
-- 归属该跟哪一条无从判定——**停下来报出来，不许随便挑一条**。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM t_process_instance GROUP BY form_data_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'P4a: 存在被多个实例引用的 t_form_data，归属回填无法判定，请先人工处理';
    END IF;
END $$;

-- 1) 实例：按行自己的 started_dept_id 映射到公司，并**标记为推断**。
--    为什么是推断而非事实：`V20__authorization_and_audit.sql:88-94` 当年就是
--    `SET started_dept_id = u.dept_id FROM t_user u WHERE u.id = pi.started_by`——
--    用的是**迁移当时**的用户部门。所以已有数据里 started_dept_id 本身就是推断值；
--    部门此后是否改过归属无法证明。正确说法是"在能确认归属未变化的范围内可准确回填"。
UPDATE t_process_instance pi
SET company_id = d.company_id,
    company_inferred = true
FROM t_department d
WHERE d.id = pi.started_dept_id
  AND pi.company_id IS NULL;

-- 2) 表单数据：沿用所属实例的归属（**必须在第 1 步之后**）。
UPDATE t_form_data fd
SET company_id = pi.company_id,
    company_inferred = pi.company_inferred
FROM t_process_instance pi
WHERE pi.form_data_id = fd.id
  AND fd.company_id IS NULL;

-- 3) 其余一律保持 NULL（待确认）：started_dept_id IS NULL 的实例（系统/无部门管理员发起）、
--    以及**没有实例的** t_form_data（无流程提交、草稿）——后者没有部门快照可依，
--    按今天的部门去猜正是口径禁止的。

CREATE INDEX IF NOT EXISTS ix_process_instance_company
    ON t_process_instance(company_id, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_form_data_company
    ON t_form_data(company_id, created_at DESC);
