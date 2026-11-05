# Integration guide: adding the color gate to an existing service

Step-by-step guide for taking `color-gate-spring-boot-starter` from this repository into an existing Spring Boot
service that runs blue/green on GKE. Written so that an engineer or an AI coding agent can follow it.

How it works and why: [design.md](design.md). What happens when things go wrong: [negative-scenarios.md](negative-scenarios.md).

---

## 0. Context for an AI agent (read first)

**The ask:** each tier runs in its own GKE cluster with two namespaces, `blue` and `green`. During a release both
colors run. Background work (`@Scheduled`, cron, calendar/polling tasks, startup tasks, timers, the execution
engine's "resubmit EXECUTING jobs") must run **only in the active color**. A ConfigMap per cluster says which color is
active; pods are **told** about changes by a Kubernetes informer (push, no polling).

**Fixed decisions (do not redesign):**

1. Source of truth: ConfigMap `color-gate` in namespace `color-gate`, one per cluster, key `active-color`, values
   `BLUE`, `GREEN`, `NONE`. Only the pipeline writes it.
2. Pods read it with a fabric8 informer (list + watch). No polling, no database, no file mounts.
3. A pod's own color comes from its namespace (`blue`/`green`), or `APP_COLOR`; if both exist they must agree.
4. Release order: deploy new color (passive) → smoke test → switch load balancers → **stop the old color and wait
   until its pods are gone** → write the new color. Never flip while old pods still exist; never force-delete them.
5. Invalid value, deleted ConfigMap or lost API connection: **keep the current state** (and log loudly).
6. Missing RBAC (HTTP 401/403): **fail startup**.
7. Job table gets one column `executed_by_color` (nullable, no default). Recovery: `SWITCH` resubmits only jobs not
   started by this color; `STARTUP` resubmits all `EXECUTING` jobs.
8. Do **not** add per-job heartbeats, drain mode, fencing tokens, file-mounted ConfigMaps or DB-based switches; they
   were considered and rejected ([design.md](design.md), section 9).

---

## 1. What you are adding

```
 cluster (one per tier)
 ┌──────────────────────────────────────────────────────────────────────┐
 │ namespace color-gate:  ConfigMap color-gate   active-color: GREEN     │  ← pipeline writes (kubectl patch)
 │                          ▲ watch (push)              ▲ watch (push)   │
 │ namespace blue:   service pods (library inside)    namespace green: … │
 └──────────────────────────────────────────────────────────────────────┘
```

| Who | One-time work | Per release |
|---|---|---|
| Platform / DevOps | Apply RBAC + create ConfigMap in each cluster (section 4) | — |
| Base Helm chart owner | Check 3 small items (section 5) | — |
| Each service team | Add dependency; small code changes (section 7); one DB column if it has an execution engine | — |
| Pipeline | Add pre-check, switch, post-check steps (section 9) | Runs them |

---

## 2. Bring the library into your organisation

1. Copy `color-gate-spring-boot-starter/` into your shared-libraries repository (with `gradle.properties`'s
   `fabric8Version`).
2. Rename the package and coordinates, e.g. `com.example.colorgate` → `com.yourcompany.platform.colorgate`. Update:
   - all Java packages (main and test);
   - `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`;
   - **`ColorGateAspect`**: the pointcut strings contain fully-qualified annotation names
     (`...RunInAllColors`, `...ColorGated`). If they are not updated, the gate silently stops matching;
   - `group`/`version` in the build.
3. Add your repository to the `publishing { repositories { … } }` block in the starter's `build.gradle`, then:
   ```bash
   ./gradlew :color-gate-spring-boot-starter:test :color-gate-spring-boot-starter:publish
   ```
   For a quick local trial instead: `./gradlew :color-gate-spring-boot-starter:publishToMavenLocal`.

**Dependency footprint:** the starter brings `spring-boot-starter-aop` and the fabric8 Kubernetes client (JDK HTTP
client, no Vert.x). The fabric8 client includes the Kubernetes model classes, about 15 MB in the service jar.
**It brings no database dependency.**

## 3. Add the dependency to the service

Gradle (Groovy):
```groovy
dependencies {
    implementation 'com.example.colorgate:color-gate-spring-boot-starter:0.0.1-SNAPSHOT'
}
```
Maven:
```xml
<dependency>
    <groupId>com.example.colorgate</groupId>
    <artifactId>color-gate-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

If the service has actuator, expose the gate's endpoint (the pipeline checks use it):
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, colorgate
```

## 4. Kubernetes setup, once per cluster (DevOps)

Run for **each** cluster (tier). Files are in [`k8s/`](../k8s).

```bash
kubectl --context <cluster> apply -f k8s/color-gate-rbac.yaml
```
Creates namespace `color-gate`, plus a Role "read ConfigMap color-gate" bound to all pods in `blue` and `green`.
Edit the namespace names in the file if yours differ.

```bash
kubectl --context <cluster> create -f k8s/color-gate-configmap.yaml
```
Creates the ConfigMap with the color that is live today (edit `active-color` first). Use `create`, never `apply`
again, and never put it in a Helm chart or GitOps sync: re-applying would reset the color.

Only if the pipeline can't already write ConfigMaps in `color-gate` (edit the subject first):
```bash
kubectl --context <cluster> apply -f k8s/color-gate-pipeline-rbac.yaml
```

Check the permissions:
```bash
kubectl --context <cluster> auth can-i watch configmaps/color-gate -n color-gate --as=system:serviceaccount:green:default
```
```bash
kubectl --context <cluster> auth can-i update configmaps -n color-gate
```
(The second one checks your own identity; run it as the pipeline.) Both must answer `yes`.

## 5. Base Helm chart (once)

See [`k8s/base-chart-snippet.yaml`](../k8s/base-chart-snippet.yaml). Usually nothing changes:

| Item | Needed? |
|---|---|
| `automountServiceAccountToken` | Must not be `false` (default is `true`): pods need their token to talk to the Kubernetes API |
| `APP_COLOR` env var | Optional: the color comes from the namespace name. Set it only if namespaces aren't named `blue`/`green` (or `*-blue`/`*-green`) |
| `terminationGracePeriodSeconds` | Recommended ~15 s: the old color's shutdown time is most of the switch window |
| NetworkPolicy | If pod egress is restricted, allow the Kubernetes API server |

The ConfigMap is **not** part of the chart.

## 6. Service configuration

Nothing is required: the defaults match the setup above. All properties:

| Property | Default | Meaning |
|---|---|---|
| `colorgate.enabled` | `true` | `false` where there is no blue/green (local, dev): always active, no Kubernetes needed |
| `colorgate.my-color` | env `APP_COLOR`, then namespace | This pod's color |
| `colorgate.config-map.namespace` | `color-gate` | Where the ConfigMap lives |
| `colorgate.config-map.name` | `color-gate` | ConfigMap name |
| `colorgate.config-map.key` | `active-color` | Key with BLUE, GREEN or NONE |
| `colorgate.kubernetes.connection-timeout` | `5s` | Client connect timeout |
| `colorgate.kubernetes.request-timeout` | `10s` | Client request timeout |

For local development without Kubernetes, put in `application-local.yml`:
```yaml
colorgate:
  enabled: false
```
If the service already defines a `KubernetesClient` bean, the library uses it.

## 7. Code changes

### 7.1 Find all background work

Run from the service root:
```bash
grep -rn --include='*.java' -E "@Scheduled|@Schedules" src/main
```
```bash
grep -rn --include='*.java' -E "ApplicationReadyEvent|ApplicationStartedEvent|ContextRefreshedEvent|implements .*(ApplicationRunner|CommandLineRunner|SmartLifecycle)|@PostConstruct" src/main
```
```bash
grep -rn --include='*.java' -E "new Timer\(|TimerTask|ScheduledExecutorService|newScheduledThreadPool|newSingleThreadScheduledExecutor|TaskScheduler|new Thread\(|while \(true\)" src/main
```
```bash
grep -rn --include='*.java' "EXECUTING" src/main
```

### 7.2 What to change

| What you find | Change |
|---|---|
| `@Scheduled` (fixedRate, fixedDelay, cron) | **None.** Gated automatically. Add `@RunInAllColors` only for pod-local work (in-memory cache refresh) |
| Startup work touching shared state (DB, queues, other services) | Replace the hook with `@OnColorActivated` |
| Pure in-pod startup (warming a local cache) | Leave as is |
| `TimerTask`, executor loop, own thread doing shared work | First line of each tick `if (!colorGate.isActive()) return;`, or `@ColorGated` on the method the loop calls |
| Something started in `@OnColorActivated` that runs on its own | Optionally stop it in `@OnColorDeactivated` (only fires for NONE or a flip while running; never on shutdown) |
| Execution engine | Section 7.3 |

Startup hook, before and after:
```java
@EventListener(ApplicationReadyEvent.class)       // before
public void loadPendingAndSubmit() { ... }

@OnColorActivated                                 // after
public void loadPendingAndSubmit() { ... }
```
For `ApplicationRunner` / `CommandLineRunner`: move the body into an `@OnColorActivated` method.

`@ColorGated` works through the Spring proxy: the call must come from another bean, and the method should return
`void` (a skipped call returns `null`).

### 7.3 Execution engine

**(a) Database: one column on the job table** (Flyway/Liquibase migration):
```sql
ALTER TABLE job ADD COLUMN executed_by_color VARCHAR(10);   -- nullable, NO default, no backfill
```
NULL means "started by code that predates the column" and counts as another color's job at a switch.

**(b) Stamp the color wherever a job becomes `EXECUTING`** (scheduled, manual, recovery, retry). Keep it in one
method or repository so no path forgets it. Reference: `sample-service/.../engine/JobRepository.java`.
```java
jdbc.update("UPDATE job SET status = 'EXECUTING', executed_by_color = ?, started_at = now() WHERE id = ?",
        myColor(), id);

private String myColor() {
    Color color = colorGate.getMyColor();          // null when colorgate.enabled=false
    return color == null ? null : color.name();
}
```

**(c) Recovery: replace the startup hook.** Reference: `sample-service/.../engine/ExecutionEngine.java`.
```java
@OnColorActivated
public void resubmitExecutingJobs(ColorActivatedEvent event) {
    List<Job> orphans = event.reason() == ColorActivatedEvent.Reason.SWITCH
            ? jobRepository.findExecutingNotStartedBy(myColor())   // release: only the stopped color's jobs
            : jobRepository.findExecuting();                       // pod (re)start: all, as before
    orphans.forEach(this::resubmit);                               // resubmit stamps the new color
}
```
```sql
SELECT * FROM job WHERE status = 'EXECUTING' AND (executed_by_color IS NULL OR executed_by_color <> ?)
```

**(d) Shutdown must leave running jobs `EXECUTING`** (as after a crash). If the engine marks them FAILED/STOPPED on
SIGTERM, recovery will not find them.

**(e) Manual job endpoints: no change.** They may run in a passive pod (smoke tests); the stamped color protects them.

**(f) Keep recovery handlers quick** (enqueue the resubmits). Gated work in the pod starts only after they return.

## 8. Database changes: summary

| Table | Change |
|---|---|
| Job / execution table | `executed_by_color VARCHAR(10)`, nullable, no default (only services with an execution engine) |
| Everything else | None. The color gate itself uses no database. |

## 9. Pipeline changes

All tiers switch together. `CLUSTERS` lists the kube contexts of all tier clusters; `SVC_PORT` is the management
port of your services.

### 9.1 Pre-check (before stopping anything)

Every pod of the new color in every cluster must be **synced and PASSIVE**. This proves RBAC, network and the
informer work before anything is stopped. Using the API server's pod proxy (no tools needed in the container):
```bash
for CTX in $CLUSTERS; do
  for POD in $(kubectl --context "$CTX" -n green get pods -o jsonpath='{.items[*].metadata.name}'); do
    kubectl --context "$CTX" get --raw "/api/v1/namespaces/green/pods/${POD}:${SVC_PORT}/proxy/actuator/colorgate"
  done
done
```
Expect for each: `"myColor":"GREEN","state":"PASSIVE"` and `"synced":true`. Any pod not synced: stop the release.
(The pipeline needs `pods/proxy` permission for this; logs (`starts PASSIVE`) or metrics are alternatives.)

### 9.2 Switch

1. Take the release lock (one release at a time).
2. Switch the load balancers to green (existing step).
3. Record replica counts of blue (needed for rollback), then stop blue in all clusters:
   ```bash
   for CTX in $CLUSTERS; do kubectl --context "$CTX" -n blue scale deploy --all --replicas=0; done
   ```
4. Wait until **no** blue pods exist in **any** cluster (timeout e.g. 5 min). On timeout: stop, alert, do not switch,
   **never force-delete** stuck pods:
   ```bash
   for CTX in $CLUSTERS; do
     until [ -z "$(kubectl --context "$CTX" -n blue get pods -o name)" ]; do sleep 1; done
   done
   ```
5. Write the new color in all clusters (in parallel), with audit annotations:
   ```bash
   for CTX in $CLUSTERS; do
     kubectl --context "$CTX" -n color-gate patch configmap color-gate --type merge -p \
       "{\"data\":{\"active-color\":\"GREEN\"},\"metadata\":{\"annotations\":{\"colorgate/switched-at\":\"$(date +%s)000\",\"colorgate/switched-by\":\"pipeline-${BUILD_ID}\"}}}" &
   done; wait
   ```
6. Read back all clusters; fail on any mismatch:
   ```bash
   for CTX in $CLUSTERS; do kubectl --context "$CTX" -n color-gate get configmap color-gate -o jsonpath='{.data.active-color}{"\n"}'; done
   ```

### 9.3 Post-check

Within ~30 s every green pod reports `"state":"ACTIVE"` (same command as 9.1). Otherwise alert; do not blindly roll
back (see [negative-scenarios.md](negative-scenarios.md)).

### 9.4 Rollback (green → blue)

Scale blue back to its recorded replica counts (it starts PASSIVE), switch the load balancers back, scale green to 0
and wait until its pods are gone, then patch `active-color` to `BLUE`. Blue resubmits only green's `EXECUTING` jobs.
Data compatibility (old code reading what new code wrote) is the real rollback risk.

### 9.5 Emergency stop

```bash
for CTX in $CLUSTERS; do kubectl --context "$CTX" -n color-gate patch configmap color-gate --type merge -p '{"data":{"active-color":"NONE"}}'; done
```
All background work stops within about a second; pods keep running and serving requests; jobs already running
continue. Resume with the color that should be active.

## 10. First rollout

1. **Release N** (first release with the library), deployed the normal way into today's live namespace, say `blue`:
   - before it: section 4 in every cluster, ConfigMap created with `active-color: BLUE`;
   - with it: the `executed_by_color` migration;
   - pods start, see BLUE = their color, activate (`STARTUP`): behaviour unchanged.
2. **Release N+1**: the first real blue/green release, using section 9.

If the ConfigMap is missing, pods start PASSIVE and **no background work runs** (logged as a warning).

## 11. Verify in a dev cluster

1. Both colors running: `/actuator/colorgate` shows `ACTIVE` on one color only, `PASSIVE` + `synced` on the other.
2. Passive color: no scheduled activity. To see skipped ticks, set `logging.level.com.example.colorgate.ColorGateAspect=DEBUG`.
3. Manual job on the passive color: it runs, `executed_by_color` is that color.
4. Switch (section 9): new color logs `active color is GREEN (pushed, N ms after the switch)`, then
   `Recovery (SWITCH)` with exactly the old color's `EXECUTING` count; its own jobs untouched.
5. Restart one pod of the active color: `becoming ACTIVE (STARTUP)`, all `EXECUTING` jobs resubmitted.
6. No job ran twice:
   ```sql
   SELECT executed_by_color, status, count(*) FROM job GROUP BY 1, 2 ORDER BY 1, 2;
   ```
7. Try the failure cases in [negative-scenarios.md](negative-scenarios.md) that apply.

## 12. Monitoring and alerts

With actuator/Micrometer in the service, the library registers three gauges (tag `color`), read only when your
monitoring scrapes them:

| Gauge | Meaning | Suggested alert |
|---|---|---|
| `colorgate.active` | 1 while this pod may run background work | Sum over the active color's pods = 0 for > 2 min outside a release: "no background work running" |
| `colorgate.watching` | 1 while the push connection to the Kubernetes API is up | = 0 for > 5 min: "pod can't hear switches" (it keeps its state meanwhile) |
| `colorgate.synced` | 1 once the active color has been read | = 0 for > 2 min after a pod start: RBAC, network or API problem |

Also useful: alert on the log lines `was DELETED`, `expected BLUE, GREEN or NONE` and `HTTP 403`.

## 13. Guidelines for service teams

**Do**
- Treat `@OnColorActivated` as "startup" for anything that touches shared state.
- Keep `@OnColorActivated` handlers short; hand long work to an executor.
- Make "become EXECUTING" a single code path that stamps `executed_by_color`.
- Use `@RunInAllColors` only for work that is truly local to the pod.
- Use `colorgate.enabled=false` for local runs.

**Don't**
- Don't start shared background work from `@PostConstruct`, `ApplicationRunner` or plain threads without a gate check.
- Don't call `@ColorGated` methods from inside the same class (the proxy is bypassed).
- Don't let shutdown mark running jobs FAILED.
- Don't put the color-gate ConfigMap in a Helm chart or GitOps sync.
- Don't flip the color while old-color pods still exist; don't force-delete stuck pods to "unblock" a release.

## 14. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Startup fails: `Kubernetes refused ... (HTTP 403). Check the color-gate Role and RoleBinding` | RBAC missing, or pod namespace not in the RoleBinding | Section 4; check with `kubectl auth can-i` |
| Startup fails: `cannot tell this pod's color` | Namespace not blue/green and no `APP_COLOR` | Set `APP_COLOR`, or `colorgate.enabled=false` locally |
| Startup fails: `APP_COLOR ... says GREEN but the pod runs in namespace 'blue'` | Wrong env value in the chart | Fix the values file |
| Pod stays `UNKNOWN`, logs `cannot reach the Kubernetes API yet` | API server unreachable (NetworkPolicy, outage) | Check egress to the API server; the pod keeps retrying |
| All pods `PASSIVE`, log `ConfigMap ... does not exist` | ConfigMap not created in this cluster | Section 4 |
| Log `has active-color='GREN', expected BLUE, GREEN or NONE` | Typo written to the ConfigMap | Write a valid value; pods kept their state meanwhile |
| Log `ConfigMap ... was DELETED` | Someone deleted it | Recreate it with the right value; pods kept their state meanwhile |
| Jobs ran twice after a switch | Color flipped while old pods were still running | Pipeline step 9.2.4 (wait for zero pods) |
