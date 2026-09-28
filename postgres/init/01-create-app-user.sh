#!/bin/sh
set -eu

psql \
  --set=ON_ERROR_STOP=1 \
  --set=app_username="$DB_USERNAME" \
  --set=app_password="$DB_PASSWORD" \
  --set=database_name="$POSTGRES_DB" \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE :"app_username" LOGIN PASSWORD :'app_password';
GRANT CONNECT ON DATABASE :"database_name" TO :"app_username";
GRANT USAGE, CREATE ON SCHEMA public TO :"app_username";
SQL
