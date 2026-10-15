ALTER TABLE t_mobile_file ADD COLUMN duration_seconds DOUBLE PRECISION;
ALTER TABLE t_mobile_file ADD CONSTRAINT ck_mobile_file_duration
    CHECK (duration_seconds IS NULL OR
        (duration_seconds >= 0 AND duration_seconds < 'Infinity'::double precision));
