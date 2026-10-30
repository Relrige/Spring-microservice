---
status: DONE
service: infrastructure
---
# Kubernetes Security: Network Isolation and Pod Hardening

## Context
Group assignment, in two parts.

**Network isolation.** Reuse the existing manifests of the microservices, the databases and the Ingress, and:
- add a NetworkPolicy with a default-deny rule for all unauthorized inbound traffic in the namespace;
- allow traffic to a business service only from the Ingress or the gateway, selected by `app`;
- allow connections to a database only from its authorized business service.

**Pod hardening.** Every pod gets a `securityContext`:
- containers run as a non-root user;
- privilege escalation is forbidden (`allowPrivilegeEscalation: false`);
- the container file system is read-only (`readOnlyRootFilesystem: true`);
- `automountServiceAccountToken: false` for the workloads that do not talk to the Kubernetes API.

**Expected result.** The gateway and the Ingress still route client requests to the business services, direct network access to a database is blocked, and pods run as non-root with no privilege escalation and a read-only file system.

**Report.** The three simplest ways to take the project's cluster down and how to prevent them, plus the results of testing the protection. The project archive and the report are submitted.

*References:* [Network policies](../../../k8s/network/network-policies.yaml), [Ingress](../../../k8s/ingress/ingress.yaml), [API Gateway spec, trust boundary](../../design/services-specs/api-gateway-spec.md)

## Decisions (2026-10-27)
1. Branch `infrastructure/k8s-security` from `grafana`: PR #41 changes the same manifests (observability workloads, network policies). The PR targets `grafana` and moves to `main` once #41 is merged.
2. Scope: every pod in `voltstore`: the 9 services, the frontend, 8 PostgreSQL databases, Kafka, Mailpit, Prometheus, Alertmanager, the collectors, Tempo, Loki and Grafana.
3. PostgreSQL runs as the image's own `postgres` user (uid 70 in the alpine image) with `fsGroup: 70`; the socket directory and `/tmp` are `emptyDir`. The data on the existing volumes already belongs to that user.
4. `readOnlyRootFilesystem: true` everywhere; the paths a program must write get an `emptyDir` (for example `/tmp` for the JVM). An image that cannot run read-only is documented as a deviation with its reason.
5. `automountServiceAccountToken: false` in the pod spec of every workload except Prometheus (pod discovery) and `otel-agent` (`k8sattributes`), which use the Kubernetes API.
6. Beyond the assignment, every container also drops all Linux capabilities (`capabilities.drop: [ALL]`) and uses the runtime's default seccomp profile (`seccompProfile: RuntimeDefault`).
7. Business services keep accepting traffic from the gateway **and from the other services**: Order calls Inventory, Payment, Catalog and Delivery synchronously. Narrowing it to the gateway alone would break those calls; the report explains this.
8. Security tests are a script, `docs/reports/k8s-security/security-tests.sh`, covering:
   - routing: Ingress → gateway → service;
   - a database refusing a foreign pod but accepting its own service;
   - per pod: the user id, a failed write to `/`, no service-account token, and the flags as Kubernetes reports them.
9. The three failure scenarios of the report:
   - exhaustion of the single node's resources;
   - loss of a single-instance stateful component (the Kafka broker, a database volume);
   - a mistaken or malicious change through the Kubernetes API.

   Each comes with how to prevent and repair it.
10. Documents in English: this task file, the report `docs/reports/k8s-security.md`, and an archive script like the tracing report's.

## Acceptance Criteria
- [x] Default-deny ingress policy for the namespace; business services reachable only from the Ingress, the gateway and the other services; each database only from its own service.
- [x] Every pod: `runAsNonRoot` with an explicit non-root user, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, capabilities dropped, `seccompProfile: RuntimeDefault`.
- [x] `automountServiceAccountToken: false` on every workload that does not use the Kubernetes API.
- [x] All pods reach `Running`/`Ready` with the new settings on minikube (Calico), and the e2e checkout still works through the Ingress.
- [x] Security test script run against the cluster, its results in the report.
- [x] Report with the risks (three failure scenarios and their fixes) and the test results; archive built by a documented command, without secrets.

## Implementation Notes (2026-10-27)
- **Network isolation** needed no change: `k8s/network/network-policies.yaml` already had `default-deny-ingress`, the Ingress → frontend → gateway rules, `services-from-gateway-and-services`, and one `<svc>-db-from-<svc>` rule per database (selected by `app`).
- **Pod hardening**, in all 27 workload manifests:
  - pod level: `runAsNonRoot`, explicit `runAsUser`/`runAsGroup` (and `fsGroup` where the pod owns a volume), `seccompProfile: RuntimeDefault`, and `automountServiceAccountToken: false` (except Prometheus and the log agent);
  - container level: `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, `capabilities.drop: [ALL]`.
- **Writable paths** became `emptyDir` volumes:
  - `/tmp` for the JVMs, Grafana, Tempo, Loki and Mailpit;
  - `/var/run/postgresql` and `/tmp` for PostgreSQL;
  - `/tmp` and `/var/cache/nginx` for the frontend;
  - `/opt/kafka/config`, `/opt/kafka/logs` and `/tmp` for Kafka: the image's start script writes its final config into `/opt/kafka/config`.
- **PostgreSQL runs as uid 70** (the alpine image's `postgres`) with `fsGroup: 70`; the existing data already belonged to uid 70, so no data was migrated.
- **Mailpit** got uid 1000: its image has no user and ran as root.
- **Bug found and fixed:** the 8 database Deployments now use `strategy: Recreate` (report §4).
  - The default RollingUpdate ran the old and the new PostgreSQL on one volume for a moment and corrupted the Delivery database's WAL when the new templates rolled out.
  - It was repaired with `pg_resetwal`, run as uid 70 on the stopped volume; the data (29 shipments) was kept.
- **Tests:** `docs/reports/k8s-security/security-tests.sh` (probe pods with chosen labels, Kubernetes-reported flags, runtime checks in shell-based pods, the node's process list). The output is saved next to it.
- **Report:** `docs/reports/k8s-security.md`. **Archive:** `sh docs/reports/k8s-security/build-archive.sh`.
- **Verified** on minikube `voltstore-np` (Calico):
  - all 28 pods `1/1 Running`;
  - security tests 47 passed, 0 failed;
  - an end-to-end checkout with payment through the gateway, with the Kafka event handled by Delivery, Notification and Customer.

## Deviations from spec / task
- **Business services also accept the other services, not only the Ingress or the gateway.** The task asks for "Ingress or gateway only". Order calls Inventory, Payment, Catalog and Delivery synchronously (with `X-Internal-Token`), so a gateway-only rule breaks checkout.
  - Kept: the gateway or pods labelled `app.kubernetes.io/component: service`.
  - Alternative considered: one rule per caller pair (`app: order-service` → Inventory, …). It is narrower, but has to be updated with every new call; rejected for this project's size.
- **The log agent (`otel-agent`) runs as root.** The kubelet's files in `/var/log/pods` are readable by root only.
  - It is otherwise locked down: read-only root file system, no capabilities, no escalation, read-only `hostPath`.
  - Alternative considered: a non-root user in the root group. It needs the node's directory permissions changed outside the manifests; rejected.
- **Prometheus and the log agent keep the service-account token.** They use the Kubernetes API (pod discovery, `k8sattributes`), through namespaced read-only Roles.
- **The rollout strategy of the databases changed** (not asked by the task): a correctness fix found while applying it, described above.

## Technical Notes / Constraints
- NetworkPolicy is enforced only by a CNI that supports it: the project's minikube profile `voltstore-np` runs Calico (`k8s.txt`).
- `kubectl port-forward` enters a pod directly and is not filtered by NetworkPolicy; the tests use pods inside the cluster.
