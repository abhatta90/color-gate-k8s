#!/usr/bin/env bash
# Looks at or tampers with the color-gate ConfigMap directly, to try the failure scenarios.
#
#   scripts/configmap.sh show            print the ConfigMap
#   scripts/configmap.sh set-raw GREN    write any value WITHOUT validation (e.g. a typo)
#   scripts/configmap.sh delete          delete the ConfigMap
#
# For normal switching use scripts/switch.sh, which validates the value.
set -euo pipefail
source "$(dirname "$0")/k8s.sh"

case "${1:-}" in
  show)
    curl -s --max-time 5 "$CM_URL"
    echo
    ;;
  set-raw)
    [[ -n "${2:-}" ]] || { echo "usage: $0 set-raw <value>" >&2; exit 1; }
    write_color "$2"
    echo "active-color set to raw value '$2'"
    ;;
  delete)
    curl -s -o /dev/null -w 'delete: HTTP %{http_code}\n' --max-time 5 -X DELETE "$CM_URL"
    ;;
  *)
    echo "usage: $0 show | set-raw <value> | delete" >&2
    exit 1
    ;;
esac
