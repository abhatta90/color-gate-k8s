# Color gate: design

How a pod decides whether it may run background work during a blue/green release, using a Kubernetes ConfigMap
that is **pushed** to every pod by an informer.

Related: [negative-scenarios.md](negative-scenarios.md) (failure cases),
[integration-guide.md](integration-guide.md) (adopting it), [local-testing.md](local-testing.md) (trying it).

---

## 1. Problem

- Each tier runs in its own GKE cluster, with two namespaces, `blue` and `green`. During a release both colors run
  the same services against the same databases.
- The load balancer moves **user traffic**. **Background work** is started by each pod itself, so without a guard
  it runs in both colors: `@Scheduled` and cron jobs, a calendar task that polls the DB every second, startup tasks,
  timers, and an execution engine that resubmits `EXECUTING` jobs at startup (batch jobs of 6–8 hours, 3000+ at once).
- Jobs are scheduled and **manual** (started immediately in the pod that receives the request); smoke tests run
  manual jobs on the new color before the switch.

**Goal:** background work runs in exactly one color. The switch must be fast, generate no continuous load, and
survive failures without duplicating work.

## 2. Principles

```
 1. ONE VALUE PER CLUSTER DECIDES     ConfigMap color-gate/color-gate → active-color: BLUE | GREEN | NONE
 2. PODS ARE TOLD, THEY DON'T ASK     Kubernetes informer: list once, then the API server pushes changes
 3. ONE THREAD, DESIRED vs ACTUAL     all state changes on one thread; duplicates and bursts are harmless
 4. THE GATE IS ONE BOOLEAN READ      a gated call reads a volatile flag: run or skip, never wait or spin
 5. ONLY A VALID VALUE CHANGES STATE  missing, garbage, deleted or disconnected never flips a running pod
 6. SAFETY COMES FROM ORDERING        the old color is gone before the flip; the pipeline enforces it
```

## 3. Architecture

```
 cluster (one per tier)
 ┌───────────────────────────────────────────────────────────────────────────┐
 │ namespace color-gate                                                      │
 │   ConfigMap color-gate   data: active-color: GREEN                        │
 │                          annotations: colorgate/switched-at, -by          │
 │        ▲ watch (push)                         ▲ watch (push)              │
 │ namespace blue                                namespace green             │
 │   pods: service-a, service-b, ...             pods: service-a, ...        │
 │   (each with the color-gate starter)                                      │
 └───────────────────────────────────────────────────────────────────────────┘
   pipeline: kubectl patch configmap color-gate ... active-color=GREEN   (3 clusters, in parallel)
```

Inside one pod:

```
                  Kubernetes API server
                         │ push: "color-gate changed"
                         ▼
 ┌──────────────────────────────── pod ──────────────────────────────────────┐
 │ ConfigMapColorSource   informer on ONE ConfigMap (list + watch,           │
 │                        auto-reconnect, no resync, no polling)             │
 │        │ only valid values → desire(GREEN)        (returns immediately)   │
 │        ▼                                                                  │
 │ ColorGateReconciler    one thread "colorgate-reconciler":                 │
 │                        compare desired with state, make the transition    │
 │        │ open / close                                                     │
 │        ▼                                                                  │
 │ ColorGate              volatile boolean open                              │
 │        ▲ read (nanoseconds)                                               │
 │ ColorGateAspect        @Scheduled (unless @RunInAllColors), @ColorGated   │
 │ your code              colorGate.isActive()                               │
 └───────────────────────────────────────────────────────────────────────────┘
```

| Class | Responsibility |
|---|---|
| `ConfigMapColorSource` | fabric8 informer on `color-gate/color-gate`. First read at startup (401/403 → startup fails). Hands over valid values only; logs typos, missing keys and deletes and ignores them; "no ConfigMap" on the first read means NONE |
| `ColorGateReconciler` | Single-threaded "desired vs actual". Runs `@OnColorActivated` handlers, then opens the gate only if the value is still this pod's color. Merges bursts into one run with the latest value |
| `ColorGate` | Holds the state and the volatile `open` flag. `isActive()` never blocks |
| `ColorGateAspect` | Skips `@Scheduled` (unless `@RunInAllColors`) and `@ColorGated` methods while the gate is closed |
| `ColorGateLifecycle` | Starts the source when the application is ready; on `ContextClosedEvent` (before schedulers and beans stop) closes the gate first |
| `MyColorResolver` | The pod's own color from its namespace (`blue`/`*-blue`, `green`/`*-green`) and/or `APP_COLOR`; disagreement or no color fails startup |
| `ColorGateEndpoint` | `/actuator/colorgate`: color, state, gate, active color, informer status |
| `ColorGateMetrics` | Gauges `colorgate.active`, `colorgate.watching`, `colorgate.synced` |
| `@OnColorActivated` / `@OnColorDeactivated` | Spring event listeners for `ColorActivatedEvent(color, reason)` / `ColorDeactivatedEvent(color, activeColor)` |

## 4. States

```
                 ┌──────────── start ────────────┐
                 ▼                               │
          ┌────────────┐ first value = my color  │
          │  UNKNOWN   │─────────────────────────────►┐
          │ gate closed│                              │ reason = STARTUP
          └─────┬──────┘                              ▼
   first value: other / NONE / no ConfigMap   ┌──────────────┐ handlers done,    ┌────────────┐
                │                              │  ACTIVATING  │ still my color    │   ACTIVE   │
                ▼                              │  gate closed │ ────────────────► │ gate open  │
          ┌────────────┐  value = my color     │  @OnColor-   │                   └─────┬──────┘
          │  PASSIVE   │ ────────────────────► │  Activated   │                         │
          │ gate closed│  reason = SWITCH      └──────┬───────┘                         │
          └────────────┘ ◄────────────────────────────┴─────────────────────────────────┘
                 value = other / NONE: close the gate first, then @OnColorDeactivated
   any state ── shutdown (ContextClosedEvent) ──► STOPPED (gate closed for good)
```

- **STARTUP** vs **SWITCH** drives job recovery: on `STARTUP` (pod start/restart) all `EXECUTING` jobs are resubmitted,
  as before blue/green; on `SWITCH` only jobs not started by this color (`executed_by_color`).
- `@OnColorDeactivated` fires only when a **running** pod stops being active (NONE, or a flip while it is alive). In
  the normal release the old color is stopped first, so it never fires there; shutdown is not a color change.

## 5. The release switch

All tiers switch together:

```
 1. deploy green in every cluster          → green pods start PASSIVE (they see BLUE)
 2. pre-check: every green pod synced + PASSIVE (proves RBAC, network, informer)
 3. switch the load balancers to green
 4. scale blue to 0 in every cluster       → blue: gate closed FIRST, then jobs interrupted (left EXECUTING)
 5. wait until NO blue pod exists anywhere (timeout → stop, alert; never force-delete)
 6. patch active-color=GREEN in every cluster (parallel), read back
 7. green pods are pushed the change → recovery (SWITCH) → gate OPEN
 8. post-check: every green pod ACTIVE within 30 s
```

### Measured (local, end-to-end run against the fake API)

| Part | Time |
|---|---|
| Blue shutdown (gate closed first, then jobs interrupted) | 0.47 s |
| Writing the ConfigMap | 0.09 s |
| Push reaching green | **19 ms** after the write |
| Write → green gate open (push + recovery of 2 jobs) | 0.01 s after the write returned |
| **Whole window with no active color** | **0.57 s** |

In production the window is dominated by step 4, the old color's shutdown (application shutdown, up to
`terminationGracePeriodSeconds`), plus the pipeline's pod check and network latency to three clusters. Expect a
few seconds to about 15 s; keep `terminationGracePeriodSeconds` modest (user traffic has already moved) and the
activation handlers quick.

## 6. Decisions

| # | Decision | Chosen | Main reason |
|---|---|---|---|
| D1 | Where the active color lives | One ConfigMap per cluster in namespace `color-gate` | Blue and green can never disagree; 3 writes per switch; 2 RBAC objects per cluster |
| D2 | Values | `BLUE`, `GREEN`, `NONE` | `NONE` is an explicit emergency stop without restarting anything |
| D3 | Deleted ConfigMap / invalid value | Keep state, log loudly | An accident must not stop or start production work |
| D4 | API disconnected | Keep state; reconnect; visible in metrics | A switch is impossible while the API is down; ordering and "never force-delete" prevent stale active pods |
| D5 | Pod's own color | From the namespace name; `APP_COLOR` optional and must agree | Removes the most dangerous misconfiguration |
| D6 | RBAC 401/403 at startup | Fail startup | Fails during deployment of the new color, before any switch |
| D7 | Client | fabric8 7.9.0 with the JDK HTTP client | Standard informer implementation; no Vert.x |

## 7. Load

| | Per pod | 400 pods, 3 clusters |
|---|---|---|
| Steady state | 1 idle watch connection; renewed by the client every few minutes | ~400 idle connections; about 1 request/s in total for renewals |
| A switch | 1 pushed event | 400 events |
| Gated call on a passive pod | 1 volatile read | — |
| Database | none | none |

## 8. Testing

33 automated tests (`./gradlew build`):

| Test class | Covers |
|---|---|
| `ColorGateReconcilerTest` (12) | States, STARTUP vs SWITCH, handlers before gate, duplicates, bursts, change during activation, failing handler, shutdown, disabled mode |
| `ConfigMapColorSourceTest` (10) | Against a mock Kubernetes API: startup, push, NONE, missing ConfigMap, typo, missing key, delete + recreate, HTTP 403, API down at startup, API outage + reconnect |
| `ColorGateAutoConfigurationTest` (6) | Spring wiring: gating of `@Scheduled` / `@ColorGated` / `@RunInAllColors`, switch, metrics, shutdown, disabled mode, wrong color |
| `MyColorResolverTest` (5) | Namespace and `APP_COLOR` rules |

Plus an end-to-end run with two real application instances, Postgres and the local fake API: release switch,
manual jobs during the switch, pod restart, typo, delete, NONE, API outage, wrong and missing color
([local-testing.md](local-testing.md) has the same steps to run by hand).

## 9. Considered and rejected

| Option | Why not |
|---|---|
| Database table, polled | Needs a DB in every service; pipeline needs DB credentials; constant queries |
| ConfigMap mounted as a file | Kubelet refreshes mounted files periodically (up to ~1–2 min): too slow for the switch |
| Polling the Kubernetes API | Constant load from every pod; the informer gives push for free |
| Per-job heartbeats | One write per running job every few seconds; 3000+ jobs; framework change |
| Drain (old color finishes its jobs after the switch) | Two live colors need ownership/fencing; much more complex |
| Fencing tokens / epochs | Only needed when two colors can be alive and active at once; ordering prevents that |
| Lease ("go passive if disconnected for N s") | Pauses all background work during API outages; adds liveness detection; ordering already prevents stale active pods |
| Gating manual jobs in the passive color | Smoke tests could not exercise the new version's job execution |
| Env var + restart, admin endpoint per pod, message broker, external KV/feature-flag service | Restarts kill jobs / no stored state / extra infrastructure for one value |

## 10. Known trade-offs

- A cron fire time inside the switch window is skipped (Spring cron does not catch up).
- Jobs interrupted with the old color restart from the beginning in the new one.
- Scale-up of the active color runs `STARTUP` recovery, which (as today) also sees sibling pods' `EXECUTING` jobs;
  within-color handling stays the service's responsibility.
- The fabric8 client adds about 15 MB (Kubernetes model classes) to each service jar.
- After a long API outage, reconnection can take up to ~30 s once the API is back (client back-off).
