-- Webhook 投递可观测：记录每次投递的 HTTP 状态码与响应体（截断），成功/失败都记。
-- 背景：企业微信/钉钉等错误时仍返回 HTTP 200，真正原因在响应体 errcode 里；只存 last_error 会让故障静默成功。
ALTER TABLE t_workflow_job ADD COLUMN IF NOT EXISTS response_status INT;
ALTER TABLE t_workflow_job ADD COLUMN IF NOT EXISTS response_body TEXT;
