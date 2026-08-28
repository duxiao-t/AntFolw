-- V24 briefly marked CC tasks read when their detail page opened. Explicit acknowledgement
-- now owns this timestamp, so prior automatic values cannot be treated as user consent.
UPDATE t_task SET read_at = NULL WHERE status = 'CC' AND read_at IS NOT NULL;
