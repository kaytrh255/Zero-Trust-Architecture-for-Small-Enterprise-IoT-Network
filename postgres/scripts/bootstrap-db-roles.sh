#!/bin/sh
set -eu

: "${POSTGRES_ADMIN_PASSWORD:?Set POSTGRES_ADMIN_PASSWORD}"
: "${POSTGRES_DB:?Set POSTGRES_DB}"
: "${POSTGRES_USER:?Set POSTGRES_USER}"
: "${DB_USERNAME:?Set DB_USERNAME}"
: "${DB_PASSWORD:?Set DB_PASSWORD}"
: "${DB_MIGRATION_USERNAME:?Set DB_MIGRATION_USERNAME}"
: "${DB_MIGRATION_PASSWORD:?Set DB_MIGRATION_PASSWORD}"

if [ "$DB_USERNAME" = "$DB_MIGRATION_USERNAME" ]; then
  echo "DB_USERNAME and DB_MIGRATION_USERNAME must be different roles" >&2
  exit 1
fi

if [ "$DB_USERNAME" = "$POSTGRES_USER" ] || [ "$DB_MIGRATION_USERNAME" = "$POSTGRES_USER" ]; then
  echo "Application roles must not reuse the PostgreSQL administrator role" >&2
  exit 1
fi

if [ "$DB_PASSWORD" = "$DB_MIGRATION_PASSWORD" ] \
  || [ "$DB_PASSWORD" = "$POSTGRES_ADMIN_PASSWORD" ] \
  || [ "$DB_MIGRATION_PASSWORD" = "$POSTGRES_ADMIN_PASSWORD" ]; then
  echo "PostgreSQL administrator, runtime, and migration passwords must be different secrets" >&2
  exit 1
fi

PGPASSWORD="$POSTGRES_ADMIN_PASSWORD" psql \
  --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --host postgres \
  --port 5432 \
  --dbname "$POSTGRES_DB" <<'SQL'
\getenv runtime_username DB_USERNAME
\getenv runtime_password DB_PASSWORD
\getenv migration_username DB_MIGRATION_USERNAME
\getenv migration_password DB_MIGRATION_PASSWORD
\getenv database_name POSTGRES_DB
\getenv admin_username POSTGRES_USER

SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'migration_username')
    AS migration_role_exists \gset
\if :migration_role_exists
\else
CREATE ROLE :"migration_username" LOGIN PASSWORD :'migration_password';
\endif
ALTER ROLE :"migration_username" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'migration_password';

SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_username')
    AS runtime_role_exists \gset
\if :runtime_role_exists
\else
CREATE ROLE :"runtime_username" LOGIN PASSWORD :'runtime_password';
\endif
ALTER ROLE :"runtime_username" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'runtime_password';

ALTER DATABASE :"database_name" OWNER TO :"admin_username";
REVOKE CREATE, TEMPORARY ON DATABASE :"database_name" FROM PUBLIC;
REVOKE ALL PRIVILEGES ON DATABASE :"database_name" FROM :"migration_username";
REVOKE ALL PRIVILEGES ON DATABASE :"database_name" FROM :"runtime_username";
GRANT CONNECT ON DATABASE :"database_name" TO :"migration_username";
GRANT CONNECT ON DATABASE :"database_name" TO :"runtime_username";

SELECT set_config('phase14.migration_username', :'migration_username', false);
SELECT set_config('phase14.runtime_username', :'runtime_username', false);

DO $revoke_application_role_memberships$
DECLARE
    application_role text;
    membership_row record;
BEGIN
    FOREACH application_role IN ARRAY ARRAY[
        current_setting('phase14.runtime_username'),
        current_setting('phase14.migration_username')
    ] LOOP
        FOR membership_row IN
            SELECT granted_role.rolname
            FROM pg_auth_members membership
            JOIN pg_roles granted_role ON granted_role.oid = membership.roleid
            WHERE membership.member = (
                SELECT oid FROM pg_roles WHERE rolname = application_role
            )
        LOOP
            EXECUTE format('REVOKE %I FROM %I', membership_row.rolname, application_role);
        END LOOP;
    END LOOP;
END;
$revoke_application_role_memberships$;

GRANT CREATE ON SCHEMA public TO :"migration_username";
ALTER SCHEMA public OWNER TO :"migration_username";

DO $transfer_tables$
DECLARE
    table_row record;
    migration_role text := current_setting('phase14.migration_username');
BEGIN
    FOR table_row IN
        SELECT schemaname, tablename, tableowner::text AS tableowner
        FROM pg_tables
        WHERE schemaname = 'public'
    LOOP
        IF table_row.tableowner <> migration_role THEN
            EXECUTE format(
                'ALTER TABLE %I.%I OWNER TO %I',
                table_row.schemaname,
                table_row.tablename,
                migration_role
            );
        END IF;
    END LOOP;
END;
$transfer_tables$;

DO $transfer_sequences$
DECLARE
    sequence_row record;
    migration_role text := current_setting('phase14.migration_username');
BEGIN
    FOR sequence_row IN
        SELECT schemaname, sequencename, sequenceowner::text AS sequenceowner
        FROM pg_sequences
        WHERE schemaname = 'public'
    LOOP
        IF sequence_row.sequenceowner <> migration_role THEN
            EXECUTE format(
                'ALTER SEQUENCE %I.%I OWNER TO %I',
                sequence_row.schemaname,
                sequence_row.sequencename,
                migration_role
            );
        END IF;
    END LOOP;
END;
$transfer_sequences$;

DO $transfer_views$
DECLARE
    view_row record;
    migration_role text := current_setting('phase14.migration_username');
BEGIN
    FOR view_row IN
        SELECT schemaname, viewname, viewowner
        FROM pg_views
        WHERE schemaname = 'public'
    LOOP
        IF view_row.viewowner <> migration_role THEN
            EXECUTE format(
                'ALTER VIEW %I.%I OWNER TO %I',
                view_row.schemaname,
                view_row.viewname,
                migration_role
            );
        END IF;
    END LOOP;
END;
$transfer_views$;

DO $transfer_materialized_views$
DECLARE
    view_row record;
    migration_role text := current_setting('phase14.migration_username');
BEGIN
    FOR view_row IN
        SELECT schemaname, matviewname, matviewowner
        FROM pg_matviews
        WHERE schemaname = 'public'
    LOOP
        IF view_row.matviewowner <> migration_role THEN
            EXECUTE format(
                'ALTER MATERIALIZED VIEW %I.%I OWNER TO %I',
                view_row.schemaname,
                view_row.matviewname,
                migration_role
            );
        END IF;
    END LOOP;
END;
$transfer_materialized_views$;

DO $transfer_audit_function$
DECLARE
    migration_role text := current_setting('phase14.migration_username');
    function_owner text;
BEGIN
    SELECT pg_get_userbyid(proowner)
    INTO function_owner
    FROM pg_proc
    WHERE oid = to_regprocedure('public.reject_audit_history_mutation()');

    IF function_owner IS NOT NULL AND function_owner <> migration_role THEN
        EXECUTE format(
            'ALTER FUNCTION public.reject_audit_history_mutation() OWNER TO %I',
            migration_role
        );
    END IF;
END;
$transfer_audit_function$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON SCHEMA public FROM :"runtime_username";
GRANT USAGE, CREATE ON SCHEMA public TO :"migration_username";
GRANT USAGE ON SCHEMA public TO :"runtime_username";

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM :"runtime_username";
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM :"runtime_username";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"runtime_username";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO :"runtime_username";

ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_username" IN SCHEMA public
    REVOKE ALL ON TABLES FROM :"runtime_username";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_username" IN SCHEMA public
    REVOKE ALL ON SEQUENCES FROM :"runtime_username";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_username" IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime_username";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_username" IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO :"runtime_username";
SQL
