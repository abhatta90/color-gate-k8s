#!/usr/bin/env bash
# The pipeline's switch step: sets the active color in the color-gate ConfigMap. Every pod is told within ~1 second.
#
#   BLUE / GREEN  that color runs background work
#   NONE          emergency stop: no color runs background work (pods keep running and serving requests)
#
# Rule: stop the old color BEFORE switching to the new one. Otherwise the new color's recovery resubmits
# jobs the old color is still running.
#
# Usage: scripts/switch.sh GREEN
set -euo pipefail
source "$(dirname "$0")/k8s.sh"

value=$(echo "${1:-}" | tr '[:lower:]' '[:upper:]')
if [[ "$value" != "BLUE" && "$value" != "GREEN" && "$value" != "NONE" ]]; then
  echo "usage: $0 BLUE|GREEN|NONE" >&2
  exit 1
fi

before=$(current_color)
write_color "$value"
echo "active color: $before -> $(current_color)   ($CM_NAMESPACE/$CM_NAME via $K8S_API)"
