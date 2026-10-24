# API Gateway, Ingress and Network Trust Boundary: Kubernetes (minikube)

The [Compose gateway runbook](docker-compose-gateway.md) checks the gateway's behaviour. This one checks what only
exists in Kubernetes: the Ingress sends all external traffic to the frontend, whose nginx forwards `/api/*` to the
gateway with `/api` stripped; services are `ClusterIP` only; and `NetworkPolicy` objects block every path into a
service or database that does not come from the gateway or another service.

```
client --> Ingress (nginx) --> frontend --/api stripped--> api-gateway --> services --> own Postgres / Kafka
                    other pods in the namespace --X--> gateway, services, databases
```

Since the frontend deployment (2026-10-22) the API is no longer at the Ingress root: `http://127.0.0.1/catalog/...`
answers the web app, `http://127.0.0.1/api/catalog/...` the gateway.

Shell: **Git Bash**, from the repository root (see [README](README.md)).

## 0. A cluster that enforces NetworkPolicy
minikube's default CNI ignores `NetworkPolicy` objects silently. The cluster must be created with Calico; the
CNI cannot be changed on an existing cluster, so this **deletes the cluster and its data**:

```bash
docker compose stop
minikube delete
minikube start --cpus=6 --memory=6g --cni=calico
minikube addons disable dashboard
minikube addons enable ingress
```

A cluster created this way before can simply be started again (`minikube start`). To keep a cluster without Calico
instead, create a second one next to it (a minikube *profile*) and add `-p voltstore-np` to every `minikube` command
below; `kubectl` switches to it automatically (`kubectl config use-context minikube` switches back):

```bash
minikube start -p voltstore-np --cpus=6 --memory=6g --cni=calico
minikube -p voltstore-np addons enable ingress
```

Wait until Calico and the Ingress controller run:

```bash
kubectl wait --for=condition=Ready pods -l k8s-app=calico-node -n kube-system --timeout=300s
kubectl wait --for=condition=Ready pods -l app.kubernetes.io/component=controller -n ingress-nginx --timeout=300s
```

## 1. Images
Build (or `docker tag` from Compose, see the [Catalog ↔ Inventory runbook](k8s-catalog-inventory.md#1-images)) and
load every image. `minikube image load` does not replace an image the cluster already has unless it changed; a stale
image is the first suspect when a check fails on a detail.

```bash
S="api-gateway auth-service catalog-service customer-service delivery-service inventory-service notification-service order-service payment-service frontend"
for s in $S; do docker build -t voltstore/$s:latest ./$s; done
for s in $S; do minikube image load voltstore/$s:latest; done
```

## 2. Deploy
The namespace first, then Kafka, then everything else (as in [`k8s.txt`](../../k8s.txt)). `AUTH_JWT_SECRET` must be
the same in `auth-secrets` and `gateway-secrets`, and the internal token the same in every service's secret.

```bash
kubectl apply -f k8s/base/
kubectl apply -f k8s/kafka/
kubectl apply -f k8s/ -R
kubectl wait --for=condition=Ready pod/kafka-0 -n voltstore --timeout=300s
for d in auth-service catalog-service inventory-service customer-service order-service payment-service delivery-service api-gateway frontend; do
  kubectl rollout status deployment/$d -n voltstore --timeout=600s
done
kubectl get pods,svc,ingress,networkpolicy -n voltstore
```

Expected: all pods `1/1 Running` (a few restarts of the services on the first start are normal: they start before
their Postgres accepts connections); every Service `ClusterIP`; one Ingress with the backend `frontend:8080`; the
policies of `k8s/network/network-policies.yaml`. Readiness of the services proves that the probes (from the node) and
the connections service → database and service → Kafka pass the policies.

## 3. Only the frontend is reachable from outside; the API is under `/api`
In a **second terminal**, keep the tunnel open (it may ask for an administrator password):

```bash
minikube tunnel
```

Back in the first terminal:

```bash
G=http://127.0.0.1/api
code() { curl -s -o /dev/null -w '%{http_code}\n' "$@"; }
code -X POST $G/auth/login -H 'Content-Type: application/json' -d '{"email":"admin@voltstore.com","password":"admin-local-password"}'  # 200
code $G/catalog/categories                       # 200 (Ingress -> frontend -> gateway -> catalog)
code $G/no/such/route                            # 404 (the gateway's problem, not the app)
code -X POST $G/payments/charge -d '{}'          # 404 (internal endpoint, no route)
code $G/actuator/health                          # 404 (gateway's Actuator is on the management port)
curl -s http://127.0.0.1/catalog/categories | grep -o '<title>[^<]*</title>'   # the web app, not the API
code http://127.0.0.1/healthz                    # 200 (the frontend's probe)
```

Expected: `200`, `200`, `404`, `404`, `404`, the app's `<title>`, `200`.

Repeat steps 2–7 of the [Compose runbook](docker-compose-gateway.md) with `G=http://127.0.0.1/api`. Step 4 (product
activation) needs Catalog → Inventory (readiness call) and both services → Kafka, i.e. the service-to-service paths
the policies must keep open; step 5 reaches Customer and Delivery. For step 8 stop Inventory with
`kubectl scale deployment/inventory-service -n voltstore --replicas=0` (and back to `1`). The Order flow through the
cluster is covered by the frontend's live browser journeys (`frontend/README.md`, *Deployment*), which run against
the Ingress.

## 4. The network blocks everything except the frontend, the gateway and the services
`kubectl run -i` attaches after the pod starts, so a line printed in the first second can be missing; prefix the
command with `sleep 3;` (inside `sh -c`) if that happens.

A throw-away pod **without** VoltStore labels plays an attacker inside the cluster. It reaches neither a service,
nor the gateway (which accepts only the frontend and the Ingress controller), nor a database or Kafka (curl prints
`000` on timeout):

```bash
kubectl run np-probe -n voltstore --rm -i --restart=Never --image=curlimages/curl:8.11.1 -- \
  sh -c 'for u in http://catalog-service:8081/catalog/categories http://inventory-service:8084/inventory/stock http://auth-service:8086/auth/login http://api-gateway:8080/catalog/categories; do
           echo "$u -> $(curl -s -o /dev/null -m 5 -w %{http_code} $u)"; done
         nc -z -w 5 catalog-service-postgres 5432 && echo "postgres reachable" || echo "postgres blocked"
         nc -z -w 5 kafka 9092 && echo "kafka reachable" || echo "kafka blocked"'
```

Expected: `000` for all four URLs, `postgres blocked`, `kafka blocked`.

A pod with the frontend's label reaches the gateway, but not a service directly:

```bash
kubectl run np-probe-fe -n voltstore --rm -i --restart=Never --image=curlimages/curl:8.11.1 \
  --labels=app.kubernetes.io/component=frontend -- \
  sh -c 'echo "gateway -> $(curl -s -o /dev/null -m 5 -w %{http_code} http://api-gateway:8080/catalog/categories)"
         echo "catalog -> $(curl -s -o /dev/null -m 5 -w %{http_code} http://catalog-service:8081/catalog/categories)"'
```

Expected: `gateway -> 200`, `catalog -> 000`.

The same pod **with** the gateway's label may call a service (this proves the policy, not a broken service):

```bash
kubectl run np-probe-gw -n voltstore --rm -i --restart=Never --image=curlimages/curl:8.11.1 \
  --labels=app.kubernetes.io/component=gateway -- \
  curl -s -o /dev/null -m 5 -w '%{http_code}\n' http://catalog-service:8081/catalog/categories
```

Expected: `200`.

A pod labelled as a service reaches Kafka and other services, but not another service's database:

```bash
kubectl run np-probe-svc -n voltstore --rm -i --restart=Never --image=curlimages/curl:8.11.1 \
  --labels=app.kubernetes.io/component=service -- \
  sh -c 'nc -z -w 5 catalog-service-postgres 5432 && echo "catalog db reachable" || echo "catalog db blocked"
         nc -z -w 5 kafka 9092 && echo "kafka reachable" || echo "kafka blocked"
         echo "inventory -> $(curl -s -o /dev/null -m 5 -w %{http_code} http://inventory-service:8084/inventory/stock)"'
```

Expected: `catalog db blocked`, `kafka reachable`, `inventory -> 401` (reached; Inventory itself rejects the missing
identity). Anyone allowed to create pods with arbitrary labels can bypass label-based policies; in a real cluster
pod creation is restricted by RBAC.

`kubectl port-forward` is not filtered by the policies (it enters the pod directly), so the
[Catalog ↔ Inventory runbook](k8s-catalog-inventory.md) still works on this cluster:

```bash
kubectl port-forward -n voltstore svc/catalog-service 18081:8081 > /dev/null 2>&1 & PF=$!
curl -s -o /dev/null --retry 5 --retry-delay 1 --retry-all-errors -w '%{http_code}\n' http://localhost:18081/catalog/categories
kill $PF
```

Expected: `200`.

## 5. Stop
Stop the tunnel (Ctrl+C in its terminal), then keep the cluster:

```bash
minikube stop
```

or remove the deployment:

```bash
kubectl delete namespace voltstore
minikube stop
```
