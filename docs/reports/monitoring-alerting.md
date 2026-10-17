# Metrics, Alerting and Telegram Notifications: Setup and Test Results

Group assignment: *Prometheus and Alertmanager in Kubernetes, automatic scraping of `/actuator/prometheus`, Micrometer metrics of every type, alerting rules, a practical Firing test, notifications to Telegram*. Date of the run: 2026-10-17. Task: [monitoring-prometheus-alertmanager.md](../tasks/infrastructure/monitoring-prometheus-alertmanager.md).

## 1. Architecture

```
 voltstore namespace
 ┌──────────────────────────────────────────────────────────────────────────────────────────────┐
 │  8 Spring Boot pods                     Prometheus (Deployment)            Alertmanager        │
 │  annotations:                           kubernetes_sd_configs: pod         (Deployment)        │
 │    prometheus.io/scrape: "true"   ◄──── keep annotated, Running pods       group, dedupe,      │
 │    prometheus.io/path: /actuator/...    every 15 s: GET /actuator/prometheus inhibit, route    │
 │    prometheus.io/port: "8080"           evaluate voltstore-alerts.yml ───► POST /api/v2/alerts │
 │    (gateway: "8090", management port)   every 15 s                              │              │
 └─────────────────────────────────────────────────────────────────────────────────┼──────────────┘
                                                                                   ▼ HTTPS (egress)
                                                                    api.telegram.org/bot<TOKEN>/sendMessage
```

| What | Where |
|---|---|
| Prometheus Deployment + Service, emptyDir storage, 2-day retention | [`k8s/monitoring/prometheus-deployment.yaml`](../../k8s/monitoring/prometheus-deployment.yaml) |
| Scrape config and alerting rules (one ConfigMap) | [`k8s/monitoring/prometheus-config.yaml`](../../k8s/monitoring/prometheus-config.yaml) |
| ServiceAccount + namespaced Role for pod discovery | [`k8s/monitoring/prometheus-rbac.yaml`](../../k8s/monitoring/prometheus-rbac.yaml) |
| Alertmanager Deployment + Service | [`k8s/monitoring/alertmanager-deployment.yaml`](../../k8s/monitoring/alertmanager-deployment.yaml) |
| Routing, inhibition, Telegram receiver (Secret, placeholders only) | [`k8s/monitoring/alertmanager-secret-template.yaml`](../../k8s/monitoring/alertmanager-secret-template.yaml) |
| Telegram message layout | [`k8s/monitoring/alertmanager-templates.yaml`](../../k8s/monitoring/alertmanager-templates.yaml) |
| Prometheus may reach the services (8080), the gateway's management port (8090); only Prometheus may reach Alertmanager | [`k8s/network/network-policies.yaml`](../../k8s/network/network-policies.yaml) (last three policies) |

**Service side** (all 8 services): `micrometer-registry-prometheus` (runtime scope), `management.endpoints.web.exposure.include=health,prometheus`, common tag `application`, histogram buckets for `http.server.requests`. `/actuator/prometheus` is `permitAll` in each `SecurityConfig` next to the health probes: Prometheus has no internal token, and the endpoint is still not public, because the gateway never routes `/actuator/**` and the network policies admit only the gateway, the services and Prometheus.

**Why pod discovery with annotations** (and not static targets or `kubernetes_sd` role `endpoints`): a pod is the thing that is scraped, so every replica becomes its own target (two catalog replicas are two series, not one random one behind the Service). A new service needs only its three annotations. Endpoints discovery would also work, but it requires a Service per scrape port, and the gateway's management port is deliberately not on its Service.

## 2. Custom Micrometer Metrics

Spring Boot already exports JVM, HTTP, HikariCP, Kafka and scheduler metrics. The five below are our own, one per meter type, each placed where the type fits the question it answers.

| Type | Prometheus name | Service / class | Answers | Why this type |
|---|---|---|---|---|
| **Counter** | `voltstore_payments_total{type, outcome}` | Payment, `PaymentMetrics` ← `PaymentService.callGateway` | How many charges/refunds ended approved, declined, key_mismatch, timeout, unavailable | Only ever grows; rates and ratios come from `rate()` in PromQL. Survives restarts semantically (`rate()` handles the reset) |
| **Timer** | `voltstore_payment_gateway_seconds{type, outcome}` (+ `_bucket`) | Payment, `PaymentMetrics` | How long one gateway interaction takes, retries included | A duration of something that finishes; with `publishPercentileHistogram()` p95 is computed in Prometheus, across replicas |
| **DistributionSummary** | `voltstore_payment_amount{type, currency}` | Payment, `PaymentMetrics` | Count, sum (turnover) and max of approved amounts | Distribution of a value that is not a time |
| **Gauge** | `voltstore_outbox_pending_events` | Catalog, `OutboxMetrics` | How many outbox events are not yet in Kafka | A current level that goes up and down; read from the database at scrape time |
| **LongTaskTimer** | `voltstore_ttn_job_seconds` (`_count` running, `_sum`, `_max`) | Delivery, `TtnGenerationJob.run` | Is the TTN job running right now, and for how long | A Timer reports a run only after it ends, so a hung run would be invisible; a LongTaskTimer reports runs in progress |

Tags only take a small fixed set of values (never an order ID), so the number of series stays bounded. Unit tests: `PaymentServiceTest.Metrics` (approved → counted, timed, amount recorded; declined → counted, no amount; timeout on every attempt → counted once).

A side observation: Spring Boot itself already registers a LongTaskTimer for every `@Scheduled` method (`tasks_scheduled_execution_active_seconds`), which shows how the framework uses the same meter type.

## 3. Alerting Rules

`voltstore-alerts.yml` in the Prometheus ConfigMap. Every rule carries `severity` and `application`.

| Alert | Expression (short) | for | Severity |
|---|---|---|---|
| `ServiceDown` | `up{job="voltstore-services"} == 0` | 1m | critical |
| `ServiceMissing` | `absent(up{application="..."})` for each of the 8 services (a Deployment scaled to 0 has no `up` series at all) | 1m | critical |
| `HighHttpErrorRate` | 5xx share per service > 5 % (with a minimum traffic condition) | 2m | warning |
| `HighHttpLatency` | p95 of `http_server_requests_seconds_bucket` (without `/actuator`) > 1 s | 5m | warning |
| `PaymentFailureRateHigh` | non-approved share of `voltstore_payments_total` > 50 % (with a minimum traffic condition) | 1m | critical |
| `PaymentGatewaySlow` | p95 of `voltstore_payment_gateway_seconds_bucket` > 3 s | 5m | warning |
| `OutboxBacklogGrowing` | `max(voltstore_outbox_pending_events) > 10` | 5m | warning |
| `TtnJobRunningTooLong` | `max(voltstore_ttn_job_seconds_max) > 300` | 1m | warning |
| `JvmHeapAlmostFull` | heap used / max > 90 % | 5m | warning |

`for` is the pending period: the condition must hold on every evaluation for that long before the alert becomes *Firing*, so a single bad scrape does not wake anyone.

## 4. Alertmanager Handling

- **Grouping** by `alertname, application`: ten failing pods of one service are one Telegram message.
- **Timing:** `group_wait` 30 s (10 s for critical), `group_interval` 5 m, `repeat_interval` 4 h (1 h for critical).
- **Inhibition:** while `ServiceDown` or `ServiceMissing` fires for a service, that service's `warning` alerts are not sent (a dead service is also "slow" and "erroring"; one message is enough).
- **Receiver:** `telegram_configs` with `parse_mode: HTML`, our template `voltstore.telegram.message`, `send_resolved: true`.
- **Secrets:** the bot token and chat id are only in `k8s/monitoring/secret.yaml` (git-ignored); the committed template has placeholders. How to get both: comments at the top of the template.

## 5. Firing Test

### 5.1 Environment

A Kubernetes cluster could not be started in the environment where this run was made (kind fails there: nested containers are not allowed, `runc create failed`). The same pieces were therefore run with Docker Compose: the three services with custom metrics (Payment, Catalog, Delivery, each with its PostgreSQL, plus Kafka), Prometheus and Alertmanager with the **unchanged rules file and Alertmanager config/template extracted from the ConfigMaps/Secret**. Differences from the cluster:

- Prometheus used static targets (`payment-service:8080`, …) with the labels pod discovery would add, instead of `kubernetes_sd_configs`.
- Outbound HTTPS to `api.telegram.org` was blocked in that environment, so `api_url` of the Telegram receiver pointed to a small stub of the Bot API that answers `{"ok": true}` and logs each request. Alertmanager's Telegram integration itself (request format, rendering of the template) is unchanged; only the host differs.
- The config files were validated with the tools from the pinned images: `promtool check config` (9 rules) and `amtool check-config` → SUCCESS.

### 5.2 Scenario A: load with failing payments → `PaymentFailureRateHigh`

From 09:59:14 UTC a script sent two charges per second to `POST /payments/charge`: 8 of 10 cards end in `0001` (the mock gateway declines them), 1 in `4242` (approved) and 1 in `5000` (the mock "loses" the answer, the service retries, ≈ 2.3 s).

| Time (UTC) | Event |
|---|---|
| 09:59:35 | `PaymentFailureRateHigh` **pending** (non-approved share about 0.8) |
| 10:00:35 | **firing** (after `for: 1m`) |
| 10:00:46 | Alertmanager sends the Telegram message (critical: `group_wait` 10 s) |

### 5.3 Scenario B: crash of Payment Service → `ServiceDown`

| Time (UTC) | Event |
|---|---|
| 10:00:55 | `docker compose kill payment-service` (simulated crash) |
| 10:01:19 | `ServiceDown{application="payment-service"}` **pending** (first failed scrape) |
| 10:02:19 | **firing** |
| 10:02:29 | Telegram message sent |
| 10:02:32 | Payment Service started again |
| 10:03:04 | Alert resolved in Prometheus (scrape succeeds) |
| 10:07:29 | Telegram "RESOLVED" message: changes of an already notified group are sent at the next `group_interval` (5 m) |

In the cluster the same failure is `kubectl delete pod -l app=payment-service -n voltstore` (the Deployment brings a new pod; `ServiceDown` may not reach `for: 1m`) or `kubectl scale deployment payment-service -n voltstore --replicas=0` (no pod, so `ServiceMissing` fires instead of `ServiceDown`).

### 5.4 Messages as sent to Telegram

The `text` field of the captured `sendMessage` requests (`parse_mode=HTML`, so the tags are rendered as bold/monospace):

```
🔥 <b>FIRING</b> · <b>PaymentFailureRateHigh</b> · <code>payment-service</code>

🔴 <b>Most payments are failing</b>
80.51% of payment attempts were declined or failed over the last 5 minutes.
severity: <code>critical</code>
started: 2026-10-17 10:00:35 UTC
```

```
🔥 <b>FIRING</b> · <b>ServiceDown</b> · <code>payment-service</code>

🔴 <b>payment-service is down</b>
Prometheus has not been able to scrape payment-service for over 1 minute.
severity: <code>critical</code> · pod: <code>payment-service</code>
started: 2026-10-17 10:02:19 UTC
```

```
✅ <b>RESOLVED</b> · <b>ServiceDown</b> · <code>payment-service</code>

🟢 <b>payment-service is down</b>
Prometheus has not been able to scrape payment-service for over 1 minute.
severity: <code>critical</code> · pod: <code>payment-service</code>
started: 2026-10-17 10:02:19 UTC · resolved: 10:03:04 UTC
```

### 5.5 Other observations

- `HighHttpLatency` for Payment also fired (p95 2.3 s): the `5000` card makes every tenth request wait for the mock gateway's 2 s timeout plus a retry. The rule did what it should; the latency is by design of the test card.
- `ServiceMissing` fired for the five services that were not part of the Compose stand, as expected from `absent()`.
- Inhibition was not exercised in this run: `HighHttpLatency` became firing only after `ServiceDown` had resolved.

## 6. Repeating It in Minikube

```
kubectl apply -f k8s/base/ ; kubectl apply -f k8s/kafka/ ; kubectl apply -f k8s/ -R
# Telegram: copy k8s/monitoring/alertmanager-secret-template.yaml to k8s/monitoring/secret.yaml, fill in, then
kubectl apply -f k8s/monitoring/ ; kubectl rollout restart deployment/alertmanager -n voltstore

kubectl port-forward -n voltstore svc/prometheus 9090:9090      # Status > Target health: 8 targets UP
kubectl port-forward -n voltstore svc/alertmanager 9093:9093

# Scenario B: no pod -> ServiceMissing (about 1 min) -> Telegram; scale back -> RESOLVED
kubectl scale deployment payment-service -n voltstore --replicas=0
kubectl scale deployment payment-service -n voltstore --replicas=1

# Scenario A: declined payments (Payment is internal: port-forward, internal token from payment-secrets)
kubectl port-forward -n voltstore deploy/payment-service 18082:8080
#   then the loop from §5.2 against localhost:18082 for 2-3 minutes
```

Checklist for the demonstration: Prometheus *Alerts* page shows the alert going *Pending → Firing*; Alertmanager UI shows it active; the Telegram chat gets the FIRING message and, after recovery, RESOLVED.
