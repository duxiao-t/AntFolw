-- A WeCom UserID is the employee number and the password-login account.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM t_wecom_user_mapping mapping
        JOIN t_user user_row ON user_row.id = mapping.user_id
        WHERE user_row.employee_no IS NULL OR btrim(user_row.employee_no) = ''
           OR length(user_row.employee_no) > 64
           OR user_row.employee_no ~ '[[:space:][:cntrl:]]'
    ) THEN
        RAISE EXCEPTION 'a mapped WeCom user has an invalid employee number';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM t_wecom_user_mapping mapping
        JOIN t_user target ON target.id = mapping.user_id
        JOIN t_user owner ON owner.username = target.employee_no AND owner.id <> target.id
    ) THEN
        RAISE EXCEPTION 'a WeCom employee number is already another local username';
    END IF;
END $$;

UPDATE t_user user_row
SET username = user_row.employee_no,
    password_hash = crypt('qwer1234', gen_salt('bf', 10))
WHERE EXISTS (
    SELECT 1 FROM t_wecom_user_mapping mapping WHERE mapping.user_id = user_row.id
);

UPDATE t_auth_session session
SET revoked_at = now()
WHERE session.revoked_at IS NULL
  AND EXISTS (
      SELECT 1 FROM t_wecom_user_mapping mapping WHERE mapping.user_id = session.user_id
  );
