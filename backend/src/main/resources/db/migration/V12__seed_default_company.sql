-- Ensure organization management always has a company to attach departments to.
-- Existing installations created before this migration can have users but no company.
INSERT INTO t_company (name)
SELECT 'AntFlow'
WHERE NOT EXISTS (SELECT 1 FROM t_company);
