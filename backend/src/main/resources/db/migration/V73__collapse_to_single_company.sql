-- 收敛为单公司：把全库的公司归属统一到**最早创建的那家公司**，其余公司逻辑删除。
--
-- 为什么不写死公司 id 或名字：同一套代码部署在多台服务器上，各环境的公司名称不同
-- （本机叫 AntFlow，别的环境可能是别家的名字）。因此这里按 created_at + id 选"第一家"，
-- 这是唯一在各环境都成立的口径。created_at 相同则按 id 兜底，保证结果确定。
--
-- 本迁移只动数据、不删列（按约定先删代码、保留列）：
-- 应用的 company_id / company_inferred 保留，供回滚与后续清理使用。
--
-- 幂等性：全部 UPDATE 都带 `IS DISTINCT FROM` 判定，重复执行不会产生额外变更；
-- 公司逻辑删除也是幂等的。这样即便被手工重跑也不会破坏数据。

-- 1) 企微映射有 (company_id, department_id) / (company_id, user_id) 唯一键。
--    归并后同一目标行可能撞键（本机实测无冲突，但其它环境不保证），
--    所以先按"每个自然键只留一行"去重，避免整体迁移失败。
--    注意：这两张表是**复合主键、没有 id 列**，所以用 ctid 做稳定的行标识。
DELETE FROM t_wecom_department_mapping a
USING t_wecom_department_mapping b
WHERE a.department_id = b.department_id
  AND a.ctid > b.ctid;

DELETE FROM t_wecom_user_mapping a
USING t_wecom_user_mapping b
WHERE a.user_id = b.user_id
  AND a.ctid > b.ctid;

DO $$
DECLARE
    target BIGINT;
BEGIN
    SELECT id INTO target
    FROM t_company
    WHERE deleted = 0
    ORDER BY created_at, id
    LIMIT 1;

    -- 没有任何公司时不做任何事：空库初始化交给后续正常流程，不在这里造数据。
    IF target IS NULL THEN
        RAISE NOTICE 'V73: 未找到可用公司，跳过单公司收敛';
        RETURN;
    END IF;

    -- 2) 组织：部门全部归属到目标公司。
    UPDATE t_department SET company_id = target WHERE company_id IS DISTINCT FROM target;

    -- 3) 流程实例 / 表单数据：归属目标公司，并把"推断得来"的标记清掉。
    --    原 company_id 为 NULL 的历史行（本机 32 条）也要归位——单公司模式下它们本就属于这家公司，
    --    否则会因 scope 谓词要求 company_id 非空而永久不可见。
    UPDATE t_process_instance
       SET company_id = target, company_inferred = false
     WHERE company_id IS DISTINCT FROM target OR COALESCE(company_inferred, false);

    UPDATE t_form_data
       SET company_id = target, company_inferred = false
     WHERE company_id IS DISTINCT FROM target OR COALESCE(company_inferred, false);

    -- 4) 企微：配置、映射、同步任务不再按公司分区。
    UPDATE t_wecom_config SET company_id = target WHERE company_id IS DISTINCT FROM target;
    UPDATE t_wecom_department_mapping SET company_id = target WHERE company_id IS DISTINCT FROM target;
    UPDATE t_wecom_user_mapping SET company_id = target WHERE company_id IS DISTINCT FROM target;
    UPDATE t_wecom_sync_job SET company_id = target WHERE company_id IS DISTINCT FROM target;

    -- 5) 其余公司逻辑删除：保留行以便回滚与历史追溯，但对所有查询不可见。
    UPDATE t_company SET deleted = 1 WHERE id <> target AND deleted = 0;

    -- 6) 修正台账的 company_id_at_time 是**历史快照**，刻意不改写：
    --    它记录的是"当时那笔操作属于哪家公司"，改写它等于伪造审计。
END $$;
