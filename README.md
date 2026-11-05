# Color gate (Kubernetes informer)

Blue/green deployments run two copies of every service at once. The load balancer moves user traffic, but
**background work** (scheduled tasks, cron, polling tasks, startup tasks, the execution engine's job recovery) is
started by each pod itself and would run in **both** colors. The **color gate** makes it run only in the **active**
color.

The active color is one value in a Kubernetes **ConfigMap** per cluster. Every pod is **told** about a change by a
Kubernetes **informer** (push, no polling) within milliseconds, and checks a gate before starting background work.

```
 pipeline:  kubectl patch configmap color-gate ... active-color=GREEN
                     │  push (watch), ~20 ms
          ┌──────────┴───────────┐
   blue pods: PASSIVE       green pods: ACTIVE  →  @OnColorActivated (recovery) → gate OPEN
   @Scheduled ticks skip    @Scheduled, cron, calendar task, timers run
```

## What's in the repository

| Folder | What it is |
|---|---|
| `color-gate-spring-boot-starter/` | The library (Spring Boot 3.5 starter, Java 21, fabric8 7.9.0 informer). No database dependency |
| `sample-service/` | Demo service: mini execution engine, calendar task, `@Scheduled`, cron, `TimerTask`, startup task, manual "run now" jobs (Postgres for its jobs) |
| `dev-k8s-api/` | Development only: an in-memory fake Kubernetes API on `127.0.0.1:8001`, to try everything without a cluster |
| `k8s/` | One-time cluster setup: namespace + RBAC, the ConfigMap, pipeline permission, base Helm chart notes |
| `scripts/` | `switch.sh` (the pipeline's switch step), `configmap.sh` (show / typo / delete), `status.sh`, DB helpers |
| `docs/` | Design, failure scenarios, integration guide, local testing |
| `.run/` | IntelliJ run configurations: **local Kubernetes API**, **sample-service BLUE** (8081), **sample-service GREEN** (8082) |

## Documents

| Document | Read it for |
|---|---|
| [docs/design.md](docs/design.md) | How it works: principles, components, states, the switch timeline (measured), decisions, rejected options |
| [docs/negative-scenarios.md](docs/negative-scenarios.md) | Every failure case: what happens, why it's safe, what to do, which test proves it |
| [docs/integration-guide.md](docs/integration-guide.md) | Adding the library to an existing service: Kubernetes setup, Helm, code, database, pipeline, monitoring |
| [docs/local-testing.md](docs/local-testing.md) | Running the tests and trying the switch and failure scenarios on a laptop |

## Quick start

Prerequisites: Java 21; PostgreSQL with user `colorgate` and database `colorgate_k8s` (for the sample's jobs; see
[docs/local-testing.md](docs/local-testing.md)).

```bash
./gradlew build
```

In IntelliJ (File → Open → this folder): start **local Kubernetes API**, then **sample-service BLUE** and
**sample-service GREEN**. Then:

```bash
scripts/switch.sh BLUE
```
```bash
scripts/status.sh
```

Follow the walkthroughs in [docs/local-testing.md](docs/local-testing.md).

## Using the library in a service

1. Add the dependency.
2. DevOps, once per cluster: `k8s/color-gate-rbac.yaml` + create `k8s/color-gate-configmap.yaml`.
3. Code: `@Scheduled` needs nothing; startup hooks become `@OnColorActivated`; timers check `colorGate.isActive()`;
   the execution engine stamps `executed_by_color` and recovers by `STARTUP`/`SWITCH`.
4. Pipeline: stop the old color, wait for zero pods, then `kubectl patch` the ConfigMap in every cluster.

Details: [docs/integration-guide.md](docs/integration-guide.md).
