-- V7__user_contacts.sql
-- 通讯录所需字段：手机、职务、性别
ALTER TABLE t_user ADD COLUMN phone VARCHAR(32);
ALTER TABLE t_user ADD COLUMN position VARCHAR(64);
ALTER TABLE t_user ADD COLUMN gender VARCHAR(8);

COMMENT ON COLUMN t_user.phone IS '手机号';
COMMENT ON COLUMN t_user.position IS '职务';
COMMENT ON COLUMN t_user.gender IS '性别 (男/女)';
