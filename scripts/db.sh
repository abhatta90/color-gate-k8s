# Sourced by the other scripts: how to reach the POC database. Defines psql_cmd.
#
# - Connects over TCP to localhost, which works for Homebrew Postgres and for Postgres in Docker.
# - Uses the app's env vars DB_USER / DB_PASSWORD, or the standard PGHOST / PGPORT / PGUSER / PGPASSWORD / PGDATABASE.
# - If psql is not installed but Docker is, runs psql inside the container $PG_CONTAINER (default colorgate-pg).

export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-${DB_USER:-colorgate}}"
export PGDATABASE="${PGDATABASE:-colorgate_k8s}"
export PGOPTIONS='-c client_min_messages=warning'
if [[ -n "${PGPASSWORD:-${DB_PASSWORD:-}}" ]]; then
  export PGPASSWORD="${PGPASSWORD:-$DB_PASSWORD}"
fi

PG_CONTAINER="${PG_CONTAINER:-colorgate-pg}"

if command -v psql >/dev/null 2>&1; then
  psql_cmd() { psql "$@"; }
elif command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$PG_CONTAINER"; then
  psql_cmd() { docker exec -i -e PGPASSWORD -e PGOPTIONS "$PG_CONTAINER" psql -h localhost -U "$PGUSER" -d "$PGDATABASE" "$@"; }
else
  echo "psql not found, and no running Docker container named '$PG_CONTAINER'." >&2
  echo "Install the psql client (macOS: brew install libpq && brew link --force libpq)," >&2
  echo "or start Postgres in Docker as '$PG_CONTAINER' (docs/local-testing.md)." >&2
  exit 1
fi
