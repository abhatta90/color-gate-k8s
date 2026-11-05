# Sourced by the other scripts: how to reach the Kubernetes API and which ConfigMap holds the active color.
#
# K8S_API is a plain-HTTP Kubernetes API endpoint:
#   - the local fake API (dev-k8s-api), or
#   - a real cluster through `kubectl proxy --port=8001` (uses your kubectl login).
# Both listen on http://127.0.0.1:8001 by default.

K8S_API="${K8S_API:-http://127.0.0.1:8001}"
CM_NAMESPACE="${CM_NAMESPACE:-color-gate}"
CM_NAME="${CM_NAME:-color-gate}"
CM_KEY="${CM_KEY:-active-color}"
CM_URL="$K8S_API/api/v1/namespaces/$CM_NAMESPACE/configmaps/$CM_NAME"

if ! curl -s -o /dev/null --max-time 3 "$K8S_API/api/v1/namespaces/$CM_NAMESPACE/configmaps"; then
  echo "No Kubernetes API at $K8S_API." >&2
  echo "Start the local fake API (IntelliJ: 'local Kubernetes API'), or run: kubectl proxy --port=8001" >&2
  exit 1
fi

# Prints the active-color value, or <no ConfigMap>.
current_color() {
  local body
  body=$(curl -s --max-time 5 "$CM_URL")
  if echo "$body" | grep -q '"kind" *: *"ConfigMap"'; then
    echo "$body" | sed -n "s/.*\"$CM_KEY\" *: *\"\([^\"]*\)\".*/\1/p" | head -1
  else
    echo "<no ConfigMap>"
  fi
}

# Writes a raw value to the key (no validation), creating the ConfigMap if needed. Adds switched-at/-by annotations.
write_color() {
  local value=$1 now who annotations status
  now=$(perl -MTime::HiRes=time -e 'printf "%d", time*1000')
  who="${SWITCHED_BY:-$(whoami)@scripts}"
  annotations="\"colorgate/switched-at\":\"$now\",\"colorgate/switched-by\":\"$who\""

  status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X PATCH "$CM_URL" \
    -H 'Content-Type: application/merge-patch+json' \
    -d "{\"metadata\":{\"annotations\":{$annotations}},\"data\":{\"$CM_KEY\":\"$value\"}}")
  if [[ "$status" == "404" ]]; then
    status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST \
      "$K8S_API/api/v1/namespaces/$CM_NAMESPACE/configmaps" -H 'Content-Type: application/json' \
      -d "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"$CM_NAME\",\"namespace\":\"$CM_NAMESPACE\",\"annotations\":{$annotations}},\"data\":{\"$CM_KEY\":\"$value\"}}")
  fi
  if [[ "$status" != 2* ]]; then
    echo "Writing ConfigMap $CM_NAMESPACE/$CM_NAME failed (HTTP $status)." >&2
    echo "On a real cluster: does namespace $CM_NAMESPACE exist (k8s/color-gate.yaml) and may you update ConfigMaps?" >&2
    exit 1
  fi
}
