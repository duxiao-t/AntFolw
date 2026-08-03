ALTER TABLE t_department
ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY parent_id
               ORDER BY created_at, id
           ) AS rn
    FROM t_department
)
UPDATE t_department d
SET sort_order = ranked.rn
FROM ranked
WHERE d.id = ranked.id;

CREATE INDEX IF NOT EXISTS ix_dept_parent_sort
ON t_department (parent_id, sort_order, id);
