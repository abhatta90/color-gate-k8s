# Local testing

How to build the project, run the automated tests, and try the blue/green switch and the failure scenarios on a
laptop.

## 1. Prerequisites

- **Java 21** (`java -version`)
- **PostgreSQL**, only for the sample service's jobs (the color gate itself needs no database). On macOS:
  ```bash
  brew install postgresql@17
  ```
  ```bash
  brew services start postgresql@17
  ```
  ```bash
  createuser colorgate
  ```
  ```bash
  createdb -O colorgate colorgate_k8s
  ```
  Postgres in Docker works too: set `DB_URL`, `DB_USER`, `DB_PASSWORD` for the apps; the scripts read `DB_PASSWORD`
  and fall back to running `psql` inside a container named `colorgate-pg`.
- **A Kubernetes API**, one of:
  - **A. The local fake API** in this project: nothing to install (section 3).
  - **B. A real local cluster** such as Colima or Docker Desktop (section 4).

No Gradle install is needed (`./gradlew`).

## 2. Build and automated tests

```bash
./gradlew build
```

Runs 33 tests: the reconciler on its own, own-color resolution, the Spring wiring, and the informer against a mock
Kubernetes API (normal switch, emergency stop, typo, missing key, deleted ConfigMap, HTTP 403, API down at startup,
API outage with reconnect). See [negative-scenarios.md](negative-scenarios.md) for which test covers what.

**IntelliJ:** File → Open → the `color-gate-k8s` folder (imports as Gradle). Run configurations appear in the run
dropdown: **local Kubernetes API**, **sample-service BLUE** (port 8081, namespace `blue`), **sample-service GREEN**
(port 8082, namespace `green`).

## 3. Option A: the local fake Kubernetes API (no install)

`dev-k8s-api` is an in-memory fake Kubernetes API on `http://127.0.0.1:8001`. It stores ConfigMaps and pushes changes
to watchers like a real API server. It has no authentication or RBAC, and **loses everything when stopped**: if you
restart it while the apps run, restart the apps too (a real API server never loses its data, so this only affects
local testing).

Start it with the **local Kubernetes API** run configuration, or:
```bash
./gradlew :dev-k8s-api:run
```

The BLUE/GREEN run configurations already point the apps at it (`KUBERNETES_MASTER=http://127.0.0.1:8001`) and set
their namespace (`KUBERNETES_NAMESPACE=blue` / `green`), which is where each app takes its color from.

## 4. Option B: a real local cluster

With Colima (free, command line):
```bash
brew install colima kubectl
```
```bash
colima start --kubernetes
```
Set up the color gate once, as DevOps would in each cluster:
```bash
kubectl apply -f k8s/color-gate-rbac.yaml
```
```bash
kubectl create -f k8s/color-gate-configmap.yaml
```
Expose the cluster's API on the same address the fake API uses (keep it running in its own terminal):
```bash
kubectl proxy --port=8001
```
The run configurations and scripts now work against the real cluster unchanged. The apps run on your laptop, so they
reach the API through `kubectl proxy` with your own login: RBAC is not exercised here. RBAC is covered by the
automated test for HTTP 403 and by the dev-cluster checks in [integration-guide.md](integration-guide.md), section 11.

## 5. The scripts

| Script | What it does |
|---|---|
| `scripts/switch.sh BLUE\|GREEN\|NONE` | The pipeline's switch step: validates and writes `active-color` (plus `switched-at`/`switched-by` annotations) |
| `scripts/configmap.sh show` | Prints the ConfigMap |
| `scripts/configmap.sh set-raw <value>` | Writes any value **without validation** (to try a typo) |
| `scripts/configmap.sh delete` | Deletes the ConfigMap |
| `scripts/status.sh` | Active color, each app's gate state, jobs, jobs running per color, recent background work, duplicate and missed-cron checks |
| `scripts/sql.sh "<sql>"` | Runs one query against the sample database |
| `scripts/reset.sh` | Drops the sample job tables (stop the apps first; they recreate them) |

`K8S_API` (default `http://127.0.0.1:8001`) and the `CM_*` variables in `scripts/k8s.sh` change where the scripts
point.

## 6. Walkthrough: the release switch

Start **local Kubernetes API** (or `kubectl proxy`), then:

| Step | Do | Expect |
|---|---|---|
| 1 | `scripts/reset.sh` | Clean job tables |
| 2 | Start **sample-service BLUE** | `Color gate: ConfigMap color-gate/color-gate does not exist: no color is active` and `BLUE starts PASSIVE (active color: NONE)` (fake API: no ConfigMap yet) |
| 3 | `scripts/switch.sh BLUE` | BLUE: `active color is BLUE (pushed, N ms after the switch)`, `BLUE is becoming ACTIVE (SWITCH)`, `Recovery (SWITCH): 0 EXECUTING job(s)`, `BLUE is ACTIVE, gate OPEN` |
| 4 | `curl -X POST "localhost:8081/jobs?name=long&count=3&durationSeconds=600"` | Jobs 1–3 start in BLUE |
| 5 | `curl -X POST "localhost:8081/jobs?name=delayed&durationSeconds=30&delaySeconds=420"` | Job 4 waits 7 minutes |
| 6 | Start **sample-service GREEN** | `GREEN starts PASSIVE (active color: BLUE)` |
| 7 | `curl -X POST "localhost:8082/jobs/run-now?name=smoke&count=3&durationSeconds=300"` | Jobs 5–7 run in GREEN although it is passive (manual jobs) |
| 8 | `scripts/status.sh` | Apps: BLUE `ACTIVE`, GREEN `PASSIVE`. Running per color: BLUE 3, GREEN 3 |
| 9 | Stop **BLUE**, wait for `Process finished` | BLUE log: `BLUE gate CLOSED for shutdown` first, then `Job N interrupted by shutdown, left EXECUTING` |
| 10 | `scripts/switch.sh GREEN` | GREEN within about a second: `active color is GREEN (pushed, N ms after the switch)`, `GREEN is becoming ACTIVE (SWITCH)`, `Recovery (SWITCH): 3 EXECUTING job(s)`, `GREEN is ACTIVE, gate OPEN` |
| 11 | After the jobs finish: `scripts/status.sh` | Jobs 1–3 attempts 2 (restarted by GREEN), 4 run by GREEN, 5–7 attempts 1 (never resubmitted). Duplicate checks empty |

## 7. Walkthrough: failure scenarios

With GREEN active:

| Scenario | Do | Expect |
|---|---|---|
| Pod restart in production | `curl -X POST "localhost:8082/jobs/run-now?name=prod&durationSeconds=60"`, then stop and start GREEN | `GREEN is becoming ACTIVE (STARTUP)`, `Recovery (STARTUP): 1 EXECUTING job(s)` |
| Typo | `scripts/configmap.sh set-raw GREN` | WARN `has active-color='GREN' ... keeping the current state`; GREEN stays ACTIVE |
| Deleted ConfigMap | `scripts/configmap.sh delete` | ERROR `was DELETED; keeping the current state`; GREEN stays ACTIVE |
| Emergency stop | `scripts/switch.sh NONE` | GREEN `PASSIVE (active color: NONE), gate CLOSED`; audit row `deactivated` |
| Resume | `scripts/switch.sh GREEN` | GREEN `ACTIVE (SWITCH)` |
| API outage | Stop **local Kubernetes API**, then `curl localhost:8082/actuator/metrics/colorgate.watching` | GREEN stays ACTIVE; `colorgate.watching` drops from 1 to 0, `/actuator/colorgate` shows `"watching": false`. (Fake API only: restarting it loses its data, so restart the apps afterwards) |
| Wrong color | Run GREEN with an extra env var `APP_COLOR=BLUE` | Startup fails: `APP_COLOR / colorgate.my-color says BLUE but the pod runs in namespace 'green'` |
| No color | Run with `KUBERNETES_NAMESPACE=default` and no `APP_COLOR` | Startup fails: `cannot tell this pod's color` |
| See a pod's state | `curl localhost:8082/actuator/colorgate` | `myColor`, `state`, `gateOpen`, `activeColor`, and the informer's `watching`, `synced`, `lastProblem` |
| See the gauges | `curl localhost:8082/actuator/metrics/colorgate.active` (also `colorgate.watching`, `colorgate.synced`) | `1.0` or `0.0` |
| See skipped ticks | Add `--logging.level.com.example.colorgate.ColorGateAspect=DEBUG` to a passive app | `... is passive, skipping CalendarTask.triggerDueJobs()` every second |

## 8. Gotchas

- `scripts/reset.sh` only with both apps stopped (the apps create their tables only at startup).
- Fake API restarted while apps run: restart the apps too (see section 3).
- "Port already in use": an earlier run is still going (`lsof -ti tcp:8081`).
- If you also have the older `color-gate-poc` project: it uses database `colorgate`; this project uses
  `colorgate_k8s`, so they don't interfere. Don't run both projects' apps at the same time (same ports).
