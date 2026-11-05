#!/usr/bin/env bash
# Runs one SQL statement against the POC database, with the same connection rules as the other scripts.
# Usage: scripts/sql.sh "SELECT id, name, status, attempts, executed_by_color FROM engine_job ORDER BY id"
set -euo pipefail
source "$(dirname "$0")/db.sh"

if [[ -z "${1:-}" ]]; then
  echo "usage: $0 \"<sql>\"" >&2
  exit 1
fi
psql_cmd -P pager=off -c "$1"
