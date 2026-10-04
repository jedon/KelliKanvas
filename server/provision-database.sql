-- Run as a PostgreSQL administrator. Supply KANVAS_DB_PASSWORD in the process environment.
-- This creates a dedicated role/database on the existing instance without altering other apps.
\set ON_ERROR_STOP on
\getenv app_password KANVAS_DB_PASSWORD
SELECT format('CREATE ROLE kellikanvas_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kellikanvas_app') \gexec
SELECT 'CREATE DATABASE kellikanvas OWNER kellikanvas_app'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'kellikanvas') \gexec
