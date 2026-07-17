CREATE TABLE IF NOT EXISTS t_department_leader (
    department_id BIGINT NOT NULL REFERENCES t_department(id) ON DELETE CASCADE,
    user_id       BIGINT NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    PRIMARY KEY (department_id, user_id)
);

INSERT INTO t_department_leader (department_id, user_id)
SELECT id, leader_id
FROM t_department
WHERE leader_id IS NOT NULL
ON CONFLICT DO NOTHING;
