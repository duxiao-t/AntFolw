-- Runs once on first container start as the superuser
CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- for gen_random_uuid()
