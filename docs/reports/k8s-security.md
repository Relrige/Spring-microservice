# Kubernetes Security: Network Isolation, Pod Hardening and Failure Risks

Group assignment, in two parts.
- **Network isolation:** default-deny ingress in the namespace, business services reachable only from the Ingress, the gateway (and, see §2.2, the other services), and every database only from its own service.
- **Pod hardening:** non-root containers, no privilege escalation, a read-only root file system, and no Kubernetes API token in pods that do not use the API.
- **Report:** the three simplest ways to take the cluster down and how to prevent them, plus the results of testing the protection.

Run: 2026-10-27 on minikube, profile `voltstore-np`: one node, Calico, so NetworkPolicy is enforced. Task: [k8s-security-hardening.md](../tasks/infrastructure/k8s-security-hardening.md).

## 1. What Was Changed

| What | Where |
|---|---|
| Default-deny and the allow rules (existing, reused) | [`k8s/network/network-policies.yaml`](../../k8s/network/network-policies.yaml) |
| Pod and container `securityContext`, token mounting, writable `emptyDir` paths | every workload manifest: `k8s/<service>/app-deployment.yaml`, `k8s/<service>/db-deployment.yaml`, `k8s/kafka/`, `k8s/notification/mailpit.yaml`, `k8s/frontend/`, `k8s/monitoring/`, `k8s/observability/` |
| `strategy: Recreate` for the 8 PostgreSQL Deployments (bug found during this work, §4) | `k8s/<service>/db-deployment.yaml` |
| Security tests | [`k8s-security/security-tests.sh`](k8s-security/security-tests.sh), output in [`security-tests-output.txt`](k8s-security/security-tests-output.txt) |

## 2. Network Isolation

### 2.1 The policies

Only inbound traffic is restricted. Pods are selected by `app.kubernetes.io/component` (frontend, gateway, service, database, broker, …) and `app` (the individual workload).

| Policy | Selected pods | Allowed from |
|---|---|---|
| `default-deny-ingress` | every pod in `voltstore` | nothing: everything below is an exception |
| `frontend-from-ingress-controller` | the frontend | the `ingress-nginx` namespace (the Ingress controller), port 8080 |
| `gateway-from-ingress-controller` | the API Gateway | the Ingress controller and the frontend's `/api` proxy, port 8080 |
| `services-from-gateway-and-services` | the 8 business services | the gateway and the other services, port 8080 |
| `<svc>-db-from-<svc>` (8 policies) | `app: <svc>-postgres` | **only** `app: <svc>`, port 5432 |
| `kafka-from-services` | the Kafka broker | the services, port 9092 |
| `mailpit-smtp-from-notification-service` | Mailpit | only Notification, SMTP port |
| monitoring and observability policies | Prometheus, Alertmanager, collectors, Tempo, Loki | only their own clients (Prometheus scrapes, Grafana queries) |

The path of a client request is: **client → Ingress controller → frontend (nginx) → `/api` → API Gateway → business service → its own database.** Every hop is a separate allow rule; anything else is dropped by the default deny.

### 2.2 Why business services also accept the other services

The assignment asks to open a business service "only to the Ingress or the gateway". The project needs one more client: **the other services**. Order calls Inventory (reserve, release), Payment (charge, refund), Catalog (prices) and Delivery synchronously, with the `X-Internal-Token` header. A gateway-only rule would break checkout.

The rule therefore admits the gateway and pods labelled `component: service`, and nothing else. A pod without that label (the "intruder" of the tests) cannot reach any service. The services check the internal token on top of the network rule.

### 2.3 Databases

Each database admits exactly one client, selected by `app`. Order's database accepts `app: order-service` and refuses Catalog, Payment and any other pod, even pods inside the namespace with the `service` label (tested, §5).

## 3. Pod Hardening

### 3.1 Settings on every pod

```yaml
spec:
  automountServiceAccountToken: false      # no Kubernetes API token in the pod
  securityContext:
    runAsNonRoot: true                     # the kubelet refuses to start the container as uid 0
    runAsUser: <uid>                       # explicit, see the table
    runAsGroup: <gid>
    fsGroup: <gid>                         # only where the pod has a volume to own (databases, Kafka, monitoring)
    seccompProfile:
      type: RuntimeDefault                 # the container runtime's default system-call filter
  containers:
    - securityContext:
        allowPrivilegeEscalation: false    # no setuid binaries, no gaining privileges after start
        readOnlyRootFilesystem: true       # the image's files cannot be changed
        capabilities:
          drop: [ ALL ]                    # no Linux capabilities at all
      volumeMounts:                        # the few paths a program must write: emptyDir, gone with the pod
        - { name: tmp, mountPath: /tmp }
```

### 3.2 Per workload

| Workload | uid | Writable paths (`emptyDir` unless noted) | API token |
|---|---|---|---|
| 9 Spring Boot services (incl. the gateway) | 10001 | `/tmp` (JVM, Tomcat work files) | no |
| 8 PostgreSQL databases | 70 (`postgres` of the alpine image) | data volume (PVC), `/var/run/postgresql` (socket), `/tmp` | no |
| Frontend (nginx-unprivileged) | 101 | `/tmp` (pid and temp files), `/var/cache/nginx` | no |
| Kafka | 1000 | data volume (PVC), `/opt/kafka/config` (the image writes its final config at start), `/opt/kafka/logs`, `/tmp` | no |
| Mailpit | 1000 (the image has no user of its own and would run as root) | `/tmp` | no |
| Prometheus, Alertmanager | 65534 | their data volumes | Prometheus **yes** (pod discovery), Alertmanager no |
| Grafana, Tempo, Loki, OTel collector | 472 / 10001 | data volumes, `/tmp` | no |
| OTel agent (log shipper) | **0**, see below | its offset directory (hostPath) | **yes** (`k8sattributes`) |

### 3.3 Exceptions, and why

- **The log agent runs as root.** It reads the kubelet's files in `/var/log/pods`, which are readable by root only. It is otherwise locked down:
  - read-only root file system;
  - no capabilities;
  - no privilege escalation;
  - read-only `hostPath` for the logs.

  Running log shippers as root is common (Fluent Bit and Promtail do the same). The alternative, a non-root user in the root group, needs the node's log directory permissions changed, which is outside the cluster's manifests.
- **Two pods keep the API token**, because they use the Kubernetes API:
  - **Prometheus** finds the pods to scrape;
  - **the log agent** reads pod metadata.

  Both are limited to a namespaced Role with `get/list/watch` on pods.

## 4. A Bug Found on the Way: Two PostgreSQL Processes on One Volume

Applying the new pod templates restarted every database. The Delivery database then crash-looped with `PANIC: could not locate a valid checkpoint record`: its write-ahead log was corrupted.

**Cause.** The database Deployments used the default **RollingUpdate** strategy. It starts the new pod before stopping the old one. With a `ReadWriteOnce` volume on a single node, both PostgreSQL processes opened the same data directory for a moment and wrote the same log. The other seven databases survived by luck. The same thing could have happened on any earlier change to a database pod.

**Fix.**
- All 8 database Deployments now use `strategy: Recreate`: the old pod stops before the new one starts.
- The Delivery database was repaired with PostgreSQL's own `pg_resetwal`, run as uid 70 against the stopped volume. All 29 shipments were still there afterwards.

This is a small version of failure scenario 2 below.

## 5. Test Results

[`security-tests.sh`](k8s-security/security-tests.sh) runs every check from inside the cluster, because `kubectl port-forward` bypasses NetworkPolicy. For the network checks it starts short-lived probe pods with chosen labels. The image is `postgres:18-alpine`: `pg_isready` for databases, `wget` for HTTP. Result: **47 checks passed, 0 failed.** Full output: [`security-tests-output.txt`](k8s-security/security-tests-output.txt).

| # | Check | Result |
|---|---|---|
| 1 | `GET /api/catalog/products` from the node through the Ingress → frontend → gateway → Catalog | 200 |
| 1 | `POST /api/auth/login` through the same path | 200 |
| 2 | probe labelled `app=order-service` → `order-service-postgres:5432` | accepting connections |
| 2 | probe labelled `app=catalog-service` (another service) → `order-service-postgres:5432` | no response (blocked) |
| 2 | unlabelled probe → `payment-service-postgres:5432` | no response (blocked) |
| 3 | unlabelled probe → `order-service` | timed out (blocked) |
| 3 | probe labelled as the gateway → `order-service` | reached |
| 4 | every running pod: Kubernetes reports `runAsNonRoot`, uid ≠ 0, `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`, no `kube-api-access` volume (exceptions of §3.3) | 27 / 27 |
| 5 | in the databases, the frontend, Kafka and Grafana: `id -u` is not 0, `touch /x` → *Read-only file system*, no `/var/run/secrets/kubernetes.io/serviceaccount` | 12 / 12 |
| 6 | processes on the node: no application process runs as uid 0 (`postgres` 70, `nginx` 101, `java` 10001/1000, …) | 0 as root |

Beyond the script, an end-to-end checkout through the gateway still works:
- the order was placed and paid;
- Delivery, Notification and Customer handled the Kafka event;
- the services and Kafka do all of it on read-only file systems.

## 6. Three Simple Ways to Take the Cluster Down

### 6.1 Exhausting the single node's resources

**How.** Anything that makes pods use more memory than the node has:
- a load spike;
- a memory leak;
- a forgotten `limits` block;
- simply restarting all services at once.

The cluster has **one node**. Its kubelet believes it has 16 GiB (the Docker VM), but the minikube container is capped at 10 GiB, while the pods' memory limits add up to about 13 GiB (`kubectl describe node`: limits 83 %). This happened during the project: nine JVMs restarting together pushed the node to its then 6 GiB cap, the API server stopped answering and nothing could be managed.

**Prevention and repair.**
- Requests and limits on every pod (already there), with limits that fit the real node size.
- A `LimitRange` for defaults and a `ResourceQuota` for the namespace, so one workload cannot take everything.
- Roll out a few services at a time.
- More than one node, so the scheduler can move pods.
- To repair: free memory by scaling down non-essential workloads, or raise the node's memory, then restart the stuck pods.

### 6.2 Losing a single-instance stateful component

**How.** Every database, the Kafka broker and Prometheus run as **one replica on one volume**:
- a deleted PVC;
- a corrupted data directory (it happened, §4);
- a failed node.

Any of these takes the dependent services down. Without Kafka, no event moves; the outboxes keep the events, but nothing downstream happens. Without a database, its service answers 5xx. The observability incident (inventory scaled to 0) showed the effect: every checkout failed with 503.

**Prevention and repair.**
- `strategy: Recreate` for single-writer databases (done).
- A `PodDisruptionBudget`, so voluntary evictions never take the last replica.
- Kafka with three brokers and replication factor 3.
- PostgreSQL with a replica, or an operator such as CloudNativePG.
- Regular backups (`pg_dump`, volume snapshots) and a tested restore.
- Monitoring of the dependency: the `ServiceDown`/`ServiceMissing` alerts already fire within a minute.

### 6.3 A mistaken or malicious change through the Kubernetes API

**How.** One command is enough:
- `kubectl delete namespace voltstore`;
- `kubectl scale deploy --all --replicas=0`;
- re-applying the folders with the committed `*-secret-template.yaml` files, which overwrites the real Secrets. This also happened in the project: databases started with placeholder passwords and had to be re-created.

Anyone with cluster-admin rights, or a stolen token, can do it. Before this task, every pod carried an API token, so a compromised container was one step from the API.

**Prevention and repair.**
- **Least-privilege RBAC.** People and CI get only the verbs they need in `voltstore`, and no one uses cluster-admin day to day.
- **No token in pods that do not need it** (done, §3).
- **GitOps (Argo CD or Flux).** The cluster is reconciled from the repository, so a manual deletion or a wrong change is reverted, and every change is reviewed.
- **Secrets outside the repository.** Sealed Secrets or External Secrets, so templates can never overwrite the real values.
- **Admission policies.** Pod Security Admission at level `restricted`, plus Kyverno or Gatekeeper, to reject pods that run as root or mount host paths.
- **Repair:** re-apply from Git and restore the databases from backups. That is only possible if the backups of §6.2 exist.

## 7. Repeating It

```bash
# On the voltstore-np profile, with the stack running and images loaded
kubectl apply -f k8s/network/network-policies.yaml
kubectl apply -f k8s/<folder>/<workload>.yaml          # each changed workload; Secrets are not in these files
kubectl get pods -n voltstore                           # all 1/1 Running
sh docs/reports/k8s-security/security-tests.sh          # expect: passed: 47, failed: 0
sh docs/reports/k8s-security/build-archive.sh           # submission archive
```
