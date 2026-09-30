#!/bin/sh
# Loads schema.sql into a throwaway PostgreSQL database and runs the firm-isolation checks.
# Needs a local PostgreSQL where you can run: psql -U postgres
# Roles belong to the whole server (not to one database), so the script removes them before and after.
set -e
cd "$(dirname "$0")"
DB=isolation_check
cleanup() {
  psql -U postgres -q -c "DROP DATABASE IF EXISTS $DB" \
       -c "DROP ROLE IF EXISTS api_role" -c "DROP ROLE IF EXISTS checker_role"
}
cleanup
trap cleanup EXIT
psql -U postgres -q -c "CREATE DATABASE $DB"
psql -U postgres -q -d $DB -v ON_ERROR_STOP=1 -f ../src/main/resources/schema.sql
psql -U postgres -q -d $DB -f firm_isolation_check.sql
