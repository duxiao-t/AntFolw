-- 维护名单非空还不够：账号停用后，每张有效表单仍须至少有一名有效维护人。
CREATE OR REPLACE FUNCTION protect_last_active_form_maintainer()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    would_orphan_form BOOLEAN;
BEGIN
    IF OLD.status = 'ACTIVE' AND NEW.status <> 'ACTIVE' THEN
        -- 同一表单的并发停用按 form id 串行，避免两名维护人同时通过“还有另一人”的检查。
        EXECUTE format(
          'SELECT form.id
             FROM %I.t_form_definition form
             JOIN %I.t_form_maintainer maintenance ON maintenance.form_def_id = form.id
            WHERE maintenance.user_id = $1 AND form.deleted = 0
            ORDER BY form.id
            FOR UPDATE OF form',
          TG_TABLE_SCHEMA, TG_TABLE_SCHEMA)
        USING OLD.id;
        EXECUTE format(
          'SELECT EXISTS (
             SELECT 1
             FROM %I.t_form_maintainer own_maintenance
             JOIN %I.t_form_definition form
               ON form.id = own_maintenance.form_def_id AND form.deleted = 0
             WHERE own_maintenance.user_id = $1
               AND NOT EXISTS (
                 SELECT 1
                 FROM %I.t_form_maintainer other_maintenance
                 JOIN %I.t_user other_user ON other_user.id = other_maintenance.user_id
                 WHERE other_maintenance.form_def_id = own_maintenance.form_def_id
                   AND other_maintenance.user_id <> $1
                   AND other_user.status = ''ACTIVE''
               )
           )',
          TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA)
        INTO would_orphan_form
        USING OLD.id;
    END IF;
    IF would_orphan_form THEN
        RAISE EXCEPTION 'cannot disable the last active form maintainer'
          USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_last_form_maintainer
BEFORE UPDATE OF status ON t_user
FOR EACH ROW
EXECUTE FUNCTION protect_last_active_form_maintainer();
