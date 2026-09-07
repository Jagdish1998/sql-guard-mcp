-- The role the server is meant to connect as.
--
-- This file is the actual security boundary. Everything the application does, parsing,
-- cost checks, row caps, is there to give the model a helpful error and to stop expensive
-- reads. None of it is what makes a write impossible; this is. If sql-guard-mcp had a
-- remote-code-execution bug tomorrow, an attacker holding these credentials still could not
-- modify a row.
--
-- Grant SELECT, nothing else, and only on the schema the server is configured to expose.

CREATE ROLE sqlguard_readonly WITH LOGIN PASSWORD 'readonly_password';

-- Cannot create objects anywhere, including in public.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE sqlguard_demo FROM PUBLIC;

GRANT CONNECT ON DATABASE sqlguard_demo TO sqlguard_readonly;
GRANT USAGE ON SCHEMA public TO sqlguard_readonly;

GRANT SELECT ON ALL TABLES IN SCHEMA public TO sqlguard_readonly;

-- Tables created later are readable too, so adding a table does not silently break the demo.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO sqlguard_readonly;

-- Deliberately withheld:
--   * any privilege on schema `private`, so private.api_credentials is unreachable at the
--     database level and not merely filtered by the application
--   * SELECT on employee_salaries, which is additionally on the blocked-tables list. Two
--     independent mechanisms, so a config mistake in one does not expose it.
REVOKE ALL ON employee_salaries FROM sqlguard_readonly;

-- Belt and braces: even a bug that started a writable transaction would fail.
ALTER ROLE sqlguard_readonly SET default_transaction_read_only = on;
ALTER ROLE sqlguard_readonly SET statement_timeout = '10s';
ALTER ROLE sqlguard_readonly SET idle_in_transaction_session_timeout = '30s';
