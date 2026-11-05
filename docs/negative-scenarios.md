# Negative scenarios: what can go wrong, what the system does, what to do

Every failure case we know of, with the color gate's behaviour, why it is safe, the recommended action, and the test
that proves it. Background on the mechanism: [design.md](design.md).

**Ground rules the behaviour follows**

1. **Not knowing means closed.** A pod that has never learned the active color does no background work.
2. **Only a valid value changes a running pod.** `BLUE`, `GREEN` or `NONE` in the ConfigMap. A typo, a missing key,
   a deleted ConfigMap or a lost connection never flips a running pod; they are logged loudly instead.
3. **Safety comes from ordering.** At most one color is alive and active, because the old color is stopped before
   the color is switched. The pipeline enforces this.
4. **Permanent misconfiguration fails fast.** Wrong RBAC or wrong color configuration stops the pod at startup,
   during the deployment of the new color, long before any switch.

Test classes referenced below are in `color-gate-spring-boot-starter/src/test/java/com/example/colorgate/`;
"e2e" refers to the end-to-end run against the local fake API ([local-testing.md](local-testing.md)).

---

## A. Kubernetes API and ConfigMap

### A1. Kubernetes API unreachable when the pod starts

| | |
|---|---|
| **Example** | A pod starts during a GKE control-plane upgrade, or a NetworkPolicy blocks egress to the API server |
| **What happens** | First read fails; the pod logs `cannot reach the Kubernetes API yet ... retrying`; state `UNKNOWN`, gate **closed**. The informer keeps retrying with back-off (1 s, 2 s, 4 s ... up to ~30 s apart). When the API answers, the first value decides: own color → `ACTIVE (STARTUP)`, otherwise `PASSIVE` |
| **Why safe** | Not knowing means no background work, so nothing can run twice. HTTP requests are served normally |
| **Recommended** | Nothing for a short outage. If it persists: check egress to the API server. The pipeline pre-check (all new-color pods `synced`) stops a release before any switch |
| **Verified by** | `ConfigMapColorSourceTest.apiUnreachableAtStartupThenComesUp` |

### A2. Connection to the API lost while running

| | |
|---|---|
| **Example** | API server restart, GKE control-plane upgrade (zonal clusters), network blip |
| **What happens** | Pod **keeps its current state** (an active pod keeps working, a passive pod stays passive). The informer reconnects by itself, quietly, and catches up on anything that changed meanwhile. The outage is visible in `/actuator/colorgate` (`"watching": false`) and in the gauge `colorgate.watching` (drops from 1 to 0). A warning is logged only if a reconnect request itself fails with an error |
| **Why safe** | While the API is down nobody can switch the color either (the pipeline writes through the same API). Going passive would stop all production background work on every API blip |
| **Recommended** | Nothing for short blips. Alert on `colorgate.watching == 0` for more than a few minutes. Don't run a release while the API is unavailable |
| **Verified by** | `ConfigMapColorSourceTest.apiOutageKeepsTheStateAndTheInformerReconnects` (reconnected and caught up ~1 s after the API returned); e2e "API goes down": gate stayed open, `colorgate.watching` went 1 → 0 |

### A3. ConfigMap does not exist when the pod starts

| | |
|---|---|
| **Example** | New cluster where the bootstrap step was forgotten |
| **What happens** | Logs `ConfigMap color-gate/color-gate does not exist: no color is active`; state `PASSIVE`. If it is created later, pods react immediately (activation reason `SWITCH`) |
| **Why safe** | Unknown means nobody active |
| **Recommended** | Create it ([integration-guide.md](integration-guide.md), section 4) with the color that is live |
| **Verified by** | `ConfigMapColorSourceTest.missingConfigMapAtStartupMeansPassiveUntilItIsCreated`; e2e first start |

### A4. ConfigMap deleted while running

| | |
|---|---|
| **Example** | Someone runs `kubectl delete` by mistake, or a GitOps sync prunes it |
| **What happens** | ERROR `ConfigMap ... was DELETED; keeping the current state. To stop background work on purpose, set active-color=NONE instead.` Pods keep working as they were. Recreating it with a value is picked up immediately |
| **Why safe** | An accidental delete must not stop production. Stopping on purpose has its own value (`NONE`) |
| **Recommended** | Recreate it with the correct value. Find out who or what deleted it (keep it out of Helm/GitOps) |
| **Verified by** | `ConfigMapColorSourceTest.deletedConfigMapKeepsTheStateAndRecreatingItWorks`; e2e "ConfigMap deleted" |

### A5. Invalid value or missing key

| | |
|---|---|
| **Example** | `active-color: GREN`, `active-color: green-ish`, or the key removed |
| **What happens** | WARN `has active-color='GREN', expected BLUE, GREEN or NONE; ignoring it and keeping the current state`. Shown under `lastProblem` in `/actuator/colorgate` |
| **Why safe** | A typo can neither start nor stop anything |
| **Recommended** | The pipeline validates before writing (`scripts/switch.sh` does) and reads the value back after writing |
| **Verified by** | `ConfigMapColorSourceTest.invalidValueIsIgnoredAndStateKept`, `missingKeyIsIgnoredAndStateKept`; e2e "typo" |

### A6. Duplicate or repeated events

| | |
|---|---|
| **Example** | After a reconnect the informer re-delivers the same value; the pipeline writes GREEN twice |
| **What happens** | Nothing: the reconciler compares "what the ConfigMap says" with "what I am"; if they match, it does nothing |
| **Why safe** | Handlers (e.g. job recovery) never run twice for one switch |
| **Verified by** | `ColorGateReconcilerTest.duplicateValuesChangeNothing` |

### A7. Burst of changes, or the value changes during activation

| | |
|---|---|
| **Example** | Operator writes BLUE then immediately corrects to GREEN, while a pod is still running its activation handlers |
| **What happens** | Changes during activation are merged into one follow-up check of the **latest** value. Before opening the gate the pod checks again: if the value is no longer its color, the gate stays closed, `@OnColorDeactivated` runs and the pod is `PASSIVE` |
| **Why safe** | The gate never opens for a color that is no longer active; activation and deactivation never overlap |
| **Verified by** | `ColorGateReconcilerTest.burstDuringActivationIsHandledOnceWithTheLatestValue`, `valueChangedDuringActivationKeepsTheGateClosed` |

---

## B. Permissions and configuration

### B1. RBAC missing or wrong (HTTP 401/403) at startup

| | |
|---|---|
| **Example** | `k8s/color-gate-rbac.yaml` not applied in a cluster, or the pods' namespace not in the RoleBinding |
| **What happens** | **Startup fails** with `Kubernetes refused to let this pod read ConfigMap color-gate/color-gate (HTTP 403). Check the color-gate Role and RoleBinding.` The pod crash-loops; the Deployment of the new color never becomes ready |
| **Why safe** | Fails during the deployment of the new color, before any switch, instead of silently staying passive forever |
| **Recommended** | Apply the RBAC file; check with `kubectl auth can-i watch configmaps/color-gate -n color-gate --as=system:serviceaccount:green:default` |
| **Verified by** | `ConfigMapColorSourceTest.forbiddenFailsStartupWithAClearMessage` |

### B2. RBAC removed while pods are running

| | |
|---|---|
| **What happens** | Like A2: the watch fails, the pod keeps its state and keeps retrying, logging the error. The next pod restart fails with B1 |
| **Recommended** | Restore the RBAC objects (keep them in a platform chart / GitOps so they can't drift) |

### B3. Pod's own color configured wrong

| | |
|---|---|
| **Example** | `APP_COLOR=GREEN` in namespace `blue`; `APP_COLOR=RED`; namespace `payments` with no `APP_COLOR` |
| **What happens** | **Startup fails** with a precise message: `APP_COLOR ... says GREEN but the pod runs in namespace 'blue'`, `is 'RED', expected BLUE or GREEN`, or `cannot tell this pod's color` |
| **Why safe** | A pod running with the wrong color would duplicate background work; this is the most dangerous misconfiguration, so it never starts |
| **Recommended** | Fix the Helm values. Prefer namespace-derived color (no `APP_COLOR`) when namespaces are named blue/green |
| **Verified by** | `MyColorResolverTest`; `ColorGateAutoConfigurationTest.wrongColorFailsStartup`; e2e "wrong color", "no color" |

### B4. The ConfigMap is managed by Helm or GitOps

| | |
|---|---|
| **What happens** | Every `helm upgrade` or sync re-applies the file's value and can **flip the active color in the middle of a release** |
| **Recommended** | Never template or sync the ConfigMap. Create it once (`kubectl create`); afterwards only the switch step patches it |

---

## C. Release and pipeline

### C1. Old-color pods stuck in `Terminating`

| | |
|---|---|
| **Example** | A node becomes unreachable during the switch; its pods stay `Terminating` |
| **What happens** | The pipeline's "wait until no old-color pods" step times out and the switch is **not** written. Kubernetes keeps listing a pod until its node confirms it stopped, so "zero pods" is trustworthy |
| **Why safe** | The old pod might still be running; activating the new color now could run jobs twice |
| **Recommended** | Stop and alert. **Never force-delete** to unblock the release. A human confirms the node is really dead (e.g. the VM is gone) before deciding |

### C2. Color switched while the old color is still running (ordering mistake)

| | |
|---|---|
| **What happens** | The old color sees the change and closes its gate within about a second (`PASSIVE`, `@OnColorDeactivated`); its **already running** jobs keep running. The new color activates and its recovery resubmits those same jobs (they are `EXECUTING` with the old color), so **they run twice** |
| **Recommended** | This is the one rule the pipeline must enforce (section 9.2 of the integration guide). If it happened: stop the old color now; check for duplicates with the job table query |
| **Verified by** | Same mechanism as the POC's "break the rule" scenario |

### C3. Switch only partly written (pipeline died after some clusters)

| | |
|---|---|
| **What happens** | Clusters disagree. In each cluster the pods follow their own ConfigMap, so each cluster is internally consistent; no cluster runs both colors |
| **Recommended** | Re-run the switch step (it is idempotent), then read back all clusters and fail on any mismatch |

### C4. Two releases at the same time

| | |
|---|---|
| **What happens** | Last write wins |
| **Recommended** | A release lock in the pipeline (one blue/green release at a time) |

### C5. Wrong value written by the pipeline (e.g. switched to the color that was just stopped)

| | |
|---|---|
| **What happens** | No live pod has that color, so **no color is active** and background work stops (requests still served) |
| **Recommended** | Post-check: every new-color pod must report `ACTIVE` within ~30 s, otherwise alert. Fix by writing the right color |

### C6. Emergency stop (`NONE`)

| | |
|---|---|
| **What happens** | Every active pod closes its gate within about a second and runs `@OnColorDeactivated`. **New** background work stops; work already running continues; requests are served |
| **Recommended** | Use it for incidents (e.g. a job is corrupting data). If running jobs must stop too, put that logic in an `@OnColorDeactivated` method. Resume by writing the color that should be active |
| **Verified by** | `ConfigMapColorSourceTest.noneIsAnEmergencyStop`; e2e "emergency stop" |

---

## D. Pods and jobs

### D1. Pod crash or restart in production

| | |
|---|---|
| **What happens** | The new pod reads the ConfigMap, sees its own color: `ACTIVE (STARTUP)`, recovery resubmits **all** `EXECUTING` jobs, exactly as before blue/green |
| **Verified by** | e2e "pod restart in production" |

### D2. Scale-up of the active color

| | |
|---|---|
| **What happens** | A new pod activates with `STARTUP` and resubmits all `EXECUTING` jobs, **including jobs running on sibling pods** of the same color |
| **Why** | Same as today's startup behaviour; unchanged by the color gate |
| **Recommended** | Rely on your existing within-color handling (e.g. leader-only recovery). If there is none, this is a pre-existing issue worth fixing separately |

### D3. New-color pod crashes before the switch while running manual jobs

| | |
|---|---|
| **What happens** | Those jobs are `EXECUTING` with the new color, so the `SWITCH` recovery does not resubmit them |
| **Recommended** | They are picked up by the next pod restart (`STARTUP`) or resubmitted manually, like any crash today |

### D4. Pod shut down while it is activating

| | |
|---|---|
| **What happens** | Shutdown closes the gate first (`STOPPED`); the activation finishes without opening the gate |
| **Verified by** | `ColorGateReconcilerTest.stopClosesTheGateForGood`; `ColorGateAutoConfigurationTest.shutdownClosesTheGateBeforeAnythingElse` |

### D5. An `@OnColorActivated` handler throws

| | |
|---|---|
| **What happens** | ERROR logged, the gate opens anyway (running the handlers again could repeat work they already did) |
| **Recommended** | Make handlers idempotent where possible; alert on the error log |
| **Verified by** | `ColorGateReconcilerTest.failingHandlerStillOpensTheGate` |

### D6. Jobs running in the old color at the switch

| | |
|---|---|
| **What happens** | Interrupted by the old color's shutdown, left `EXECUTING`, resubmitted by the new color and **restarted from the beginning** |
| **Recommended** | Accepted trade-off. Use the "running right now, per color" query before stopping the old color to pick a quiet moment |
| **Verified by** | e2e "the switch" |

### D7. A cron time falls inside the switch window

| | |
|---|---|
| **What happens** | That run is skipped (no color is active for those seconds); Spring cron does not catch up |
| **Recommended** | Accepted for v1. Avoid releasing at the exact time of important crons. Possible later fix: write `switched-at` and replay missed crons on activation |

---

## Quick reference

| Situation | Pod state afterwards | Background work |
|---|---|---|
| API down at startup | UNKNOWN until reachable | Off |
| API down while running | Unchanged | Unchanged |
| ConfigMap missing at startup | PASSIVE | Off |
| ConfigMap deleted while running | Unchanged | Unchanged |
| Invalid value / missing key | Unchanged | Unchanged |
| `NONE` | PASSIVE everywhere | Off (running work finishes) |
| RBAC 401/403 at startup | Pod fails to start | — |
| Wrong own color | Pod fails to start | — |
| Old pods stuck Terminating | Pipeline does not switch | Old color's state |
