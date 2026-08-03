-- V2__seed.sql
-- Admin user + baseline roles. Password for both is `ant.design` (BCrypt cost 10).
-- Hash below was generated locally with `org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder`.

INSERT INTO t_role (code, name) VALUES
    ('admin', 'System administrator'),
    ('user',  'Regular user');

INSERT INTO t_user (dept_id, username, password_hash, display_name, email, status)
VALUES (NULL, 'admin', '$2a$10$N3AYwXp11ce9Nwq8Go24luLpcHkI7OiKzyBx7OAsPdoSQ6GOV9t8G', 'AntFlow Admin', 'admin@antflow.local', 'ACTIVE');

-- bob / user — used as second user for multi-assignee tests
INSERT INTO t_user (dept_id, username, password_hash, display_name, email, status)
VALUES (NULL, 'bob', '$2a$10$N3AYwXp11ce9Nwq8Go24luLpcHkI7OiKzyBx7OAsPdoSQ6GOV9t8G', 'Bob Approver', 'bob@antflow.local', 'ACTIVE');

INSERT INTO t_user_role (user_id, role_id)
SELECT u.id, r.id FROM t_user u, t_role r WHERE u.username IN ('admin','bob') AND r.code = 'user';
INSERT INTO t_user_role (user_id, role_id)
SELECT u.id, r.id FROM t_user u, t_role r WHERE u.username = 'admin' AND r.code = 'admin';
