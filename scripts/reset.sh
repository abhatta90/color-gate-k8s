#!/usr/bin/env bash
# Deletes the sample service's job tables and data so you can start the scenarios again.
# Stop both apps first; they recreate their tables on the next start.
# (The active color lives in the ConfigMap, not here: use scripts/switch.sh or scripts/configmap.sh for that.)
set -euo pipefail
source "$(dirname "$0")/db.sh"
psql_cmd -c "DROP TABLE IF EXISTS engine_job, audit_log"
