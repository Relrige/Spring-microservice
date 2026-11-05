# gRPC between Order and Inventory: Four Call Types and Retries

Group assignment:
- communication between the project's services over gRPC on Spring Boot 4.1 and Java 25;
- a `.proto` contract with all four call types (unary, server streaming, client streaming, bidirectional streaming);
- retries when a service is temporarily unavailable;
- tests of the methods and of the retries under failures.

Runs: 2026-11-05, unit and integration tests, and minikube (profile `voltstore-np`). Task: [grpc-order-inventory.md](../tasks/infrastructure/grpc-order-inventory.md).

## 1. What Talks to What

```
                     ┌──────────────────────────── Inventory (port 9090, gRPC) ────────────────────────────┐
 Order               │  voltstore.inventory.v1.StockService                                                │
 ─────               │                                                                                      │
 checkout ──────────►│  ReserveStock         client streaming   items of one order → one answer             │
 cancellation saga ─►│  ReleaseReservation   unary              one order → done                            │
 /orders/validate ──►│  GetStockLevels       server streaming   product ids → one level per product          │
 expiry + orphan  ──►│  ReleaseReservations  bidirectional      a batch of orders ⇄ one result per order    │
 jobs                └──────────────────────────────────────────────────────────────────────────────────────┘
        (metadata x-internal-token; W3C trace context; channel retry policy on UNAVAILABLE)
```

Order is the client and Inventory the server. This is the link checkout depends on, so retries matter there. Every call type serves a real flow of Order:

| Type | Method | Order's caller | Why this type |
|---|---|---|---|
| **Unary** | `ReleaseReservation` | the cancellation saga (EP-ORD-06), the clean-up after a failed placement | one order, one answer |
| **Client streaming** | `ReserveStock` | checkout (EP-ORD-02) | the order's items are streamed one by one; Inventory reserves all or nothing when the stream completes and answers **once** |
| **Server streaming** | `GetStockLevels` | the cart check `POST /orders/validate` (EP-ORD-01) | one request, one `StockLevel` per product streamed back |
| **Bidirectional** | `ReleaseReservations` | the payment-window expiry job and the orphan-reservation job | up to 100 releases on **one** stream instead of 100 calls; each result comes back as soon as its order is done, and one failed order does not end the stream |

Inventory keeps its HTTP reservation endpoints (EP-INV-06/07). Order's HTTP adapter for Inventory was replaced by the gRPC one behind the same port interface (`StockReservations`, plus a new `StockLevels`), so the business code did not change.

| What | Where |
|---|---|
| Contract | [`inventory-service/src/main/protobuf/inventory_stock.proto`](../../inventory-service/src/main/protobuf/inventory_stock.proto); Order's identical copy is checked by `InventoryProtoCopyTest` |
| Server | `inventory-service/.../grpc/StockGrpcService.java` (the four methods), `GrpcConfig.java` (token interceptor, exception → status), `GrpcErrors.java` |
| Client | `order-service/.../clients/InventoryGrpcAdapter.java` (the four calls), `InventoryGrpcConfiguration.java` (channel, token, attempt logging) |
| Retry policy | `order-service/src/main/resources/application.yml`, `spring.grpc.client.channel.inventory` |
| Network | Compose and the Kubernetes Service open port 9090; NetworkPolicy `inventory-grpc-from-order-service` admits only Order |

The stack is the gRPC support built into Spring Boot 4.1:
- `spring-boot-starter-grpc-server` and `spring-boot-starter-grpc-client` (Spring gRPC 1.1.2, grpc-java 1.84.1);
- `io.github.ascopes:protobuf-maven-plugin`, which `spring-boot-starter-parent` 4.1.2 preconfigures (protoc 4.36.2), so the protobuf and gRPC classes are generated at build time.

## 2. The Contract

```proto
service StockService {
  rpc ReleaseReservation(ReleaseReservationRequest) returns (ReleaseReservationResponse);   // unary
  rpc ReserveStock(stream ReserveItem) returns (ReserveStockResponse);                      // client streaming
  rpc GetStockLevels(GetStockLevelsRequest) returns (stream StockLevel);                    // server streaming
  rpc ReleaseReservations(stream ReleaseReservationRequest) returns (stream ReleaseResult); // bidirectional
}
```

Answers and errors:
- `ReserveStockResponse.outcome` is `RESERVED`, `ALREADY_RESERVED` or `INSUFFICIENT_STOCK` (with the short items). These are the HTTP `204`, `409` and `422` of EP-INV-06 as data, not errors.
- Statuses:
  - `INVALID_ARGUMENT` for a malformed request (not a UUID, items of two orders, more than 100 items, a product twice);
  - `UNAUTHENTICATED` without the internal token;
  - `UNAVAILABLE` when Inventory cannot answer right now (its database unreachable);
  - `INTERNAL` for anything else.
- Only `UNAVAILABLE` is worth repeating, so the server uses it only for transient failures.

## 3. Retries

### 3.1 Configuration (`order-service/src/main/resources/application.yml`)

```yaml
spring:
  grpc:
    client:
      channel:
        inventory:
          target: ${INVENTORY_GRPC_TARGET:localhost:9090}     # inventory-service:9090 in Compose and Kubernetes
          default:
            deadline: ${INVENTORY_DEADLINE:3s}                # ONE deadline for the whole call, every retry included
          service-config:
            method:
              - name:
                  - service: voltstore.inventory.v1.StockService   # every method; all of them are idempotent
                retry:
                  max-attempts: 4                              # 1 call + 3 retries
                  initial-backoff: 300ms                       # wait before retry n: random(0, min(0.3 s × 2^(n-1), 2 s))
                  max-backoff: 2s
                  backoff-multiplier: 2
                  retryable-status-codes: UNAVAILABLE
```

Why these values:
- **Only `UNAVAILABLE`.** That is a refused or reset connection, a server shutting down, or Inventory's database briefly gone. `INVALID_ARGUMENT` or `INTERNAL` would fail the same way again. `DEADLINE_EXCEEDED` is not retried either, because the deadline already covers every attempt.
- **Safe to repeat.**
  - A repeated `ReserveStock` for the same order answers `ALREADY_RESERVED`, which Order reads as "reserved" (an earlier attempt succeeded and its answer was lost).
  - A repeated release changes nothing.
  - For the two client-sending stream types, gRPC buffers the messages already sent and replays them on the new attempt.
- **One retry layer.** The Resilience4j retry Order used for Inventory over HTTP was removed; two layers would multiply the attempts (3 × 4). The circuit breaker stays: it sees each call once, after the channel's retries.
- **The jitter is part of the policy.** Each wait is random between 0 and the current back-off, so many clients retrying at once do not hit Inventory in lockstep.

### 3.2 Recording the attempts

The retry policy runs below the client interceptors: one logical call, several transport attempts. So the log lines come from a `ClientStreamTracer`, which gRPC creates for **every attempt** (`InventoryGrpcConfiguration.AttemptLogging`):

```
gRPC Inventory <method>: attempt N (retry N-1 of the call)        WARN, when an attempt is a retry
gRPC Inventory <method>: attempt N failed with <STATUS> (<description>)   WARN, when an attempt ends with an error
```

## 4. Tests

| Test | What it proves |
|---|---|
| Inventory `StockGrpcServiceIntegrationTest` (7) | The four methods against the real service, database and interceptors: streamed items reserved at once, `ALREADY_RESERVED` on a repeat, short items listed, items of two orders refused (`INVALID_ARGUMENT`), unary release idempotent, levels streamed in request order (unknown product = `known: false`), the bidirectional stream answering each request with one bad id not ending it, no token → `UNAUTHENTICATED` |
| Order `InventoryGrpcAdapterTest` (10) | Order's four calls against a fake Inventory on a local port, **with the retry policy exactly as in application.yml**: the client stream carries every item and the token; `UNAVAILABLE` twice → success on the 3rd attempt (and the replayed stream reaches the server whole); every call type is retried; `UNAVAILABLE` every time → 4 attempts, then `DependencyUnavailableException`; `INTERNAL` → 1 attempt, no retry |
| Order `InventoryGrpcConnectionTest` (2) | Real connection failures, not scripted ones: nothing listens on the port → 4 refused attempts (`io exception`) and the error; Inventory starts 300 ms after the first attempt → the call succeeds after its retries |
| Order `InventoryProtoCopyTest` | Order's copy of the contract is identical to Inventory's |
| Order integration tests (Checkout, Cancellation, Fulfillment/Expiry) | The flows end to end with PostgreSQL and Kafka and the fake gRPC Inventory: checkout reserves on the client stream, Inventory down → 4 attempts and `503` with the clean-up release, `/orders/validate` refuses more than Inventory has and still answers when Inventory is down, the jobs release on the bidirectional stream |

Results: Order 386 tests, Inventory 404 tests, all green.

The attempt log of two of these tests is in [`grpc/test-retries.log`](grpc/test-retries.log):

```
# UNAVAILABLE twice, the third attempt succeeds (no failure line for it)
19:14:08.543 WARN gRPC Inventory ReserveStock: attempt 1 failed with UNAVAILABLE (scripted failure)
19:14:08.544 WARN gRPC Inventory ReserveStock: attempt 2 (retry 1 of the call)
19:14:08.795 WARN gRPC Inventory ReserveStock: attempt 2 failed with UNAVAILABLE (scripted failure)
19:14:08.795 WARN gRPC Inventory ReserveStock: attempt 3 (retry 2 of the call)

# Nothing listens on Inventory's port
19:14:14.208 WARN gRPC Inventory ReleaseReservation: attempt 1 failed with UNAVAILABLE (io exception)
19:14:14.208 WARN gRPC Inventory ReleaseReservation: attempt 2 (retry 1 of the call)
19:14:14.527 WARN gRPC Inventory ReleaseReservation: attempt 2 failed with UNAVAILABLE (io exception)
19:14:14.528 WARN gRPC Inventory ReleaseReservation: attempt 3 (retry 2 of the call)
19:14:15.233 WARN gRPC Inventory ReleaseReservation: attempt 3 failed with UNAVAILABLE (io exception)
19:14:15.233 WARN gRPC Inventory ReleaseReservation: attempt 4 (retry 3 of the call)
19:14:16.640 WARN gRPC Inventory ReleaseReservation: attempt 4 failed with UNAVAILABLE (io exception)
```

**A test-only setting.** The "Inventory starts late" test lengthens the back-off: random up to 1 s, 2 s, 4 s, 4 s. After a refused connection the channel itself waits about 1 s (0.8–1.2 s) before connecting again, and attempts made in that pause fail at once. With the production waits (random up to 0.3 + 0.6 + 1.2 s), all four attempts can be spent before that reconnect. In production, a Kubernetes rollout keeps the old pod serving until the new one is ready, so a short pause like this is rare.

## 5. On the Cluster

minikube `voltstore-np`, images of both services rebuilt from this branch.

1. **Normal checkout through the gateway.** The order was placed and paid; Inventory logs `gRPC Server started, listening on address: [/[0:0:0:0:0:0:0:0]:9090]`, and its database holds the order's `ACTIVE` reservation, made by the client stream.
2. **Inventory unavailable.** `kubectl scale deploy/inventory-service --replicas=0`, then a checkout and a cart check through the gateway. Order's log, in full in [`grpc/minikube-retries.log`](grpc/minikube-retries.log):

```
19:58:20.590 WARN gRPC Inventory ReserveStock: attempt 1 failed with UNAVAILABLE (io exception)
19:58:20.590 WARN gRPC Inventory ReserveStock: attempt 2 (retry 1 of the call)
19:58:20.910 WARN gRPC Inventory ReserveStock: attempt 2 failed with UNAVAILABLE (io exception)
19:58:20.910 WARN gRPC Inventory ReserveStock: attempt 3 (retry 2 of the call)
19:58:21.544 WARN gRPC Inventory ReserveStock: attempt 3 failed with UNAVAILABLE (io exception)
19:58:21.560 WARN gRPC Inventory ReserveStock: attempt 4 (retry 3 of the call)
19:58:22.919 WARN gRPC Inventory ReserveStock: attempt 4 failed with UNAVAILABLE (io exception)
19:58:22.922 WARN ClientFailures: Inventory call failed: StatusRuntimeException: UNAVAILABLE: io exception
19:58:22.927 WARN gRPC Inventory ReleaseReservation: attempt 1 failed with UNAVAILABLE (io exception)
   … attempts 2-4 of the clean-up release …
19:58:24.965 WARN OrderPlacementService: The reservation of order 938ea0f2-… could not be released now (…); OrphanReservationJob retries
19:58:24.970 ERROR GlobalExceptionHandler: Inventory unavailable: StatusRuntimeException: UNAVAILABLE: io exception - answering 503
19:58:25.054 WARN gRPC Inventory GetStockLevels: attempt 1 failed with UNAVAILABLE (io exception)
   … attempts 2-4 …
19:58:26.968 WARN CheckoutCheckService: Checkout check without Inventory's quantities: Inventory unavailable: …
```

   What it shows:
   - The checkout's client stream was tried 4 times over 2.3 s, then the customer got `503` ("a required service is unavailable") after 4.6 s in total.
   - The unary clean-up release was also tried 4 times.
   - The cart check's server stream was tried 4 times, then the check answered `204` from Catalog alone, as decided.
3. **Back up.** After `--replicas=1`, the orphan-reservation job released the reservation attempt that the failed checkout left behind, on the bidirectional stream: `20:00:40.942 INFO OrphanReservationJob: Released the reservation of order 938ea0f2-…, which was never stored`.

## 6. Limitations

- **Plaintext inside the cluster.** The internal token travels as metadata over plaintext gRPC, like the HTTP calls. TLS or mTLS (`spring.grpc.client.channel.inventory.ssl` and the server's `ssl`) would be the next step.
- **Two copies of the contract.** The repository has no shared module, so Order keeps a copy, guarded by a test. A shared `api` module or a schema registry (Buf) would remove the copy.
- **Only pre-response failures are retried.** gRPC does not retry a streaming call once the client has received data. A server stream broken in the middle (`GetStockLevels`) fails the call, and Order then falls back to Catalog's answer.
- **One Inventory replica.** A retry only helps when Inventory comes back within the 3 s deadline. A second replica behind the Service (with gRPC client-side load balancing, `round_robin` over a headless Service) would let retries reach a healthy pod.
