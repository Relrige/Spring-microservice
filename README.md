# VoltStore

An e-commerce platform for a consumer-electronics retailer, built as nine Spring Boot microservices behind an API Gateway, with a React web app. University project for a course on microservice architecture.

| Part | Folder |
|---|---|
| API Gateway | [`api-gateway/`](api-gateway/) |
| Auth, Catalog, Customer, Inventory, Order, Payment, Delivery, Notification | `<name>-service/` |
| Web app | [`frontend/`](frontend/) |
| Kubernetes manifests | [`k8s/`](k8s/), setup commands in [`k8s.txt`](k8s.txt) |
| Specs, tasks, reports | [`docs/`](docs/) |

## Ways to run it

| Way | What runs | Needs | Open |
|---|---|---|---|
| [1. Frontend with the mock backend](#1-frontend-with-the-mock-backend) | the web app only; the backend is simulated in the browser | Node.js | http://localhost:5174 |
| [2. Everything in Docker Compose](#2-everything-in-docker-compose) | all services, databases, Kafka, Mailpit and the web app | Docker | http://localhost:3000 |
| [3. Compose backend + frontend dev server](#3-compose-backend--frontend-dev-server) | as 2, but the web app from Vite with hot reload | Docker, Node.js | http://localhost:5173 |
| [4. Kubernetes (minikube)](#4-kubernetes-minikube) | everything in a cluster, behind an Ingress, with network policies and monitoring | Docker, minikube, kubectl | http://localhost |

Way 1 is the quickest look at the UI. Way 2 is the usual way to run the whole system. Way 3 is for working on the frontend against real services. Way 4 is the deployment the project targets.

## Prerequisites

- **Docker Desktop** (ways 2–4). The whole Compose stack needs about 6–8 GB of memory for Docker.
- **Node.js 22.22 or newer** with npm (ways 1 and 3, and the demo-data seed).
- **minikube** and **kubectl** (way 4).
- **JDK 25** only to build or test a service outside Docker (`.\mvnw.cmd test` in the service folder). The Docker images build the services themselves.

Commands below are for PowerShell or Git Bash on Windows, run from the repository root unless a folder is named. On macOS or Linux use `./mvnw` and `/` in paths.

## 1. Frontend with the mock backend

No Docker, no services: the web app runs with a simulated backend (MSW) and the same demo data the real stack is seeded with.

```bash
cd frontend
npm install
npm run dev:mock
```

Open http://localhost:5174 and log in with a [demo account](#demo-accounts) (the mock's admin password is `admin-demo`). The mock keeps its data in memory: a page reload starts again from the demo data.

## 2. Everything in Docker Compose

### First time: environment files

Every service reads its settings from a git-ignored `.env` file next to its `.env.example`. Create them once:

```bash
for s in api-gateway auth-service catalog-service customer-service delivery-service inventory-service notification-service order-service payment-service; do cp -n $s/.env.example $s/.env; done
```

(PowerShell: `Copy-Item <service>\.env.example <service>\.env` for each.) The examples work as they are. If you change secrets, keep `AUTH_JWT_SECRET` equal in `api-gateway/.env` and `auth-service/.env`, and the internal token equal in every service.

### Start

```bash
docker compose up -d --build
```

The first build takes several minutes. Wait until the services are ready, then fill the stack with the demo data:

```bash
cd frontend
npm install
SEED_ADMIN_PASSWORD=admin-local-password npm run seed
```

(PowerShell: `$env:SEED_ADMIN_PASSWORD='admin-local-password'; npm run seed`.) The password is `AUTH_ADMIN_PASSWORD` from `auth-service/.env`. The seed creates the demo accounts, 4 categories and 45 products with images, stock and packaging; running it again changes nothing. If it reports `502` or `503`, a service is still starting: run it again a minute later.

### Use

| What | Address |
|---|---|
| Web app | http://localhost:3000 |
| API Gateway (the API without `/api`) | http://localhost:8080 |
| Emails sent by Notification (Mailpit) | http://localhost:8025 |
| A single service, for debugging | `localhost:8086` Auth, `8081` Catalog, `8082` Order, `8083` Delivery, `8084` Inventory, `8085` Payment, `8087` Customer, `8088` Notification |

The web app calls the API under `/api` on its own address; its nginx forwards to the gateway. The product images load from the manufacturers' sites, so they need internet access.

### Stop or reset

```bash
docker compose stop
```

`docker compose start` brings it back with the same data. To start from empty databases (this deletes all local data), remove the volumes, start again and seed:

```bash
docker compose down -v
```

## 3. Compose backend + frontend dev server

Start the backend as in [way 2](#2-everything-in-docker-compose) (the `frontend` container may run too; it is not used here), then:

```bash
cd frontend
npm run dev
```

Open http://localhost:5173. Vite forwards `/api` to the gateway on port 8080 (`VITE_API_TARGET` changes that) and reloads the page on every code change. See [`frontend/README.md`](frontend/README.md) for tests and checks.

## 4. Kubernetes (minikube)

The full sequence is in [`k8s.txt`](k8s.txt); this is the short version. Stop Compose first (`docker compose stop`): both together do not fit into Docker's memory.

### Cluster

```bash
minikube start --cpus=6 --memory=6g --cni=calico
minikube addons enable ingress
```

Calico is needed for the network policies in `k8s/network/` (minikube's default network ignores them). The network plugin cannot be changed on an existing cluster: `minikube delete` first, or use a separate profile (`-p <name>` on every `minikube` command).

### Images

Build each image and load it into the cluster:

```bash
for s in api-gateway auth-service catalog-service customer-service delivery-service inventory-service notification-service order-service payment-service frontend; do docker build -t voltstore/$s:latest ./$s && minikube image load voltstore/$s:latest; done
```

After a code change, rebuild and load that image again, then `kubectl rollout restart deployment/<name> -n voltstore`. A pod running an old image is the most common cause of surprises.

### Deploy

```bash
kubectl apply -f k8s/base/
kubectl apply -f k8s/kafka/
kubectl apply -f k8s/ -R
kubectl get pods -n voltstore -w
```

Wait until every pod is `1/1 Running` (a few restarts at the first start are normal). The `secret-template.yaml` files hold working local values. For your own values, put a `secret.yaml` (git-ignored) next to a template; apply secrets before the databases start for the first time, because a database keeps the name and user it was created with.

### Open

Keep the tunnel running in a second terminal (it may ask for an administrator password):

```bash
minikube tunnel
```

Then seed through the Ingress and open the app:

```bash
cd frontend
SEED_API_URL=http://localhost/api SEED_ADMIN_PASSWORD=admin-local-password npm run seed
```

| What | Address |
|---|---|
| Web app | http://localhost |
| API | http://localhost/api/… |
| Prometheus | `kubectl port-forward -n voltstore svc/prometheus 9090:9090`, then http://localhost:9090 |
| Alertmanager | `kubectl port-forward -n voltstore svc/alertmanager 9093:9093`, then http://localhost:9093 |
| Mailpit | `kubectl port-forward -n voltstore svc/mailpit 8025:8025`, then http://localhost:8025 |

### Watch and stop

```bash
kubectl get pods -n voltstore -w
kubectl logs -n voltstore deploy/order-service -f
minikube stop
```

`minikube delete` removes the cluster with its data. Note that deleting a database's volume alone does not empty it in minikube; see the database reset in [`docs/tasks/order-service/16-containerization-and-deployment.md`](docs/tasks/order-service/16-containerization-and-deployment.md).

## Demo accounts

Created by the seed (ways 2–4) and built into the mock (way 1):

| Role | Email | Password |
|---|---|---|
| Customer | `customer@voltstore.com` | `customer-demo` |
| Catalog manager | `manager@voltstore.com` | `manager-demo` |
| Inventory worker | `worker@voltstore.com` | `worker-demo` |
| Admin | `admin@voltstore.com` | `AUTH_ADMIN_PASSWORD` (`admin-local-password` by default); `admin-demo` in the mock |

Payment test cards: `…4242` approves, `…0002` declines.

## Checking that it works

- **Browser journeys** against a running stack: in `frontend/`, `E2E_BASE_URL=http://localhost:3000 E2E_ADMIN_PASSWORD=admin-local-password npm run test:e2e:live` (use `http://localhost` for the cluster). Details in [`frontend/README.md`](frontend/README.md).
- **Step-by-step manual checks** with `curl`, for Compose and Kubernetes: [`e2e/manual/`](e2e/manual/).
- **Service tests**: `.\mvnw.cmd test` in a service folder (JDK 25, Docker running for Testcontainers).

## Troubleshooting

| Problem | Fix |
|---|---|
| `ports are not available … 8080` | Another program uses the port (for example a local Tomcat). Stop it, or publish the gateway elsewhere with an override file (`ports: !override ["18080:8080"]` for `api-gateway`) and use `SEED_API_URL=http://localhost:18080`. |
| A service stops at start with a Flyway error | Its database volume holds an older schema. Remove that volume (`docker volume rm spring-microservice_<service>-postgres`) and start again. |
| `502 Bad Gateway` from the gateway | The service behind it is still starting or has stopped: `docker compose ps`, `docker compose logs <service>`. |
| Product images do not show | They load from the internet; offline, products show their category placeholder. |
| Something behaves like old code | The image is stale: `docker compose up -d --build <service>`, or rebuild and `minikube image load` it. |
