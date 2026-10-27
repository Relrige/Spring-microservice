#!/bin/sh
# Security tests of the Kubernetes setup (docs/tasks/infrastructure/k8s-security-hardening.md), run against the
# minikube profile voltstore-np (Calico, so NetworkPolicy is enforced). Prints PASS/FAIL per check and exits non-zero
# on any failure. Needs kubectl, minikube and node; the probe pods use the local postgres:18-alpine image (pg_isready,
# busybox wget) and are deleted after each check. Usage: sh docs/reports/k8s-security/security-tests.sh
NS=voltstore
PROFILE=${PROFILE:-voltstore-np}
pass=0
fail=0
check() { # $1 = description, $2 = 0 when the check holds
  if [ "$2" -eq 0 ]; then pass=$((pass + 1)); echo "PASS  $1"; else fail=$((fail + 1)); echo "FAIL  $1"; fi
}
# Runs a short-lived pod with the given labels and command; prints the command's output
probe() { # $1 = labels, rest = command
  labels=$1; shift
  MSYS_NO_PATHCONV=1 kubectl -n "$NS" run "probe-$(date +%s%N | tail -c 7)" --rm -i --restart=Never --quiet \
    --image=postgres:18-alpine --image-pull-policy=IfNotPresent --labels="$labels" --command -- "$@" 2>&1
}

echo "== 1. Routing: client -> Ingress -> frontend -> gateway -> business service"
status=$(minikube -p "$PROFILE" ssh -- "curl -s -o /dev/null -w '%{http_code}' 'http://localhost:31280/api/catalog/products?size=1'" | tr -d '\r')
check "GET /api/catalog/products through the Ingress answers 200 (got $status)" "$([ "$status" = 200 ]; echo $?)"
status=$(minikube -p "$PROFILE" ssh -- "curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{\"email\":\"customer@voltstore.com\",\"password\":\"customer-demo\"}' http://localhost:31280/api/auth/login" | tr -d '\r')
check "POST /api/auth/login through the Ingress answers 200 (got $status)" "$([ "$status" = 200 ]; echo $?)"

echo "== 2. Databases: only their own service may connect"
out=$(probe "app=order-service,app.kubernetes.io/component=service" pg_isready -h order-service-postgres -p 5432 -t 5)
check "a pod labelled app=order-service reaches order-service-postgres ($out)" "$(echo "$out" | grep -q 'accepting connections'; echo $?)"
out=$(probe "app=catalog-service,app.kubernetes.io/component=service" pg_isready -h order-service-postgres -p 5432 -t 5)
check "another service (app=catalog-service) is blocked from order-service-postgres ($out)" "$(echo "$out" | grep -q 'no response'; echo $?)"
out=$(probe "app=intruder" pg_isready -h payment-service-postgres -p 5432 -t 5)
check "an unlabelled intruder pod is blocked from payment-service-postgres ($out)" "$(echo "$out" | grep -q 'no response'; echo $?)"

echo "== 3. Business services: only the gateway and other services may call them"
out=$(probe "app=intruder" sh -c 'wget -q -T 5 -O - http://order-service:8082/actuator/health/liveness >/dev/null && echo reached || echo blocked')
check "an intruder pod cannot reach order-service ($out)" "$(echo "$out" | grep -q '^blocked'; echo $?)"
out=$(probe "app=api-gateway,app.kubernetes.io/component=gateway" sh -c 'wget -q -T 5 -O - http://order-service:8082/actuator/health/liveness >/dev/null && echo reached || echo blocked')
check "a pod labelled as the gateway reaches order-service ($out)" "$(echo "$out" | grep -q '^reached'; echo $?)"

echo "== 4. Pod hardening, as Kubernetes reports it (every running pod)"
kubectl -n "$NS" get pods --field-selector=status.phase=Running -o json | node -e '
  const pods = JSON.parse(require("fs").readFileSync(0)).items;
  const keepsToken = ["prometheus", "otel-agent"];   // they use the Kubernetes API
  const rootAllowed = ["otel-agent"];                // reads the root-only kubelet log files (documented deviation)
  let bad = 0;
  for (const p of pods) {
    const app = p.metadata.labels.app, ps = p.spec.securityContext || {};
    for (const c of p.spec.containers) {
      const cs = c.securityContext || {}, uid = cs.runAsUser ?? ps.runAsUser;
      const nonRoot = (cs.runAsNonRoot ?? ps.runAsNonRoot) === true && uid > 0;
      const token = p.spec.volumes?.some(v => v.name.startsWith("kube-api-access")) ?? false;
      const problems = [
        !nonRoot && !rootAllowed.includes(app) && "runs as root",
        cs.allowPrivilegeEscalation !== false && "privilege escalation allowed",
        cs.readOnlyRootFilesystem !== true && "writable root file system",
        token && !keepsToken.includes(app) && "API token mounted",
      ].filter(Boolean);
      console.log((problems.length ? "FAIL" : "PASS") + `  ${app}: uid ${uid}, nonRoot ${nonRoot}, escalation ${cs.allowPrivilegeEscalation}, readOnlyRoot ${cs.readOnlyRootFilesystem}, token ${token}` + (problems.length ? "  <- " + problems.join(", ") : ""));
      bad += problems.length > 0;
    }
  }
  process.exit(bad ? 1 : 0)' > /tmp/k8s-security-pods.txt
result=$?
cat /tmp/k8s-security-pods.txt
pass=$((pass + $(grep -c '^PASS' /tmp/k8s-security-pods.txt)))
fail=$((fail + $(grep -c '^FAIL' /tmp/k8s-security-pods.txt)))
[ "$result" -eq 0 ] || echo "(some pods above are not hardened)"

echo "== 5. Pod hardening at runtime (pods with a shell)"
for d in deploy/order-service-postgres deploy/frontend statefulset/kafka deploy/grafana; do
  uid=$(kubectl -n "$NS" exec "$d" -- id -u 2>&1)
  check "$d runs as uid $uid (not 0)" "$([ "$uid" != 0 ] && [ -n "$uid" ]; echo $?)"
  out=$(kubectl -n "$NS" exec "$d" -- sh -c 'touch /probe-write 2>&1 || true')
  check "$d cannot write to / ($out)" "$(echo "$out" | grep -q -i 'read-only'; echo $?)"
  out=$(kubectl -n "$NS" exec "$d" -- sh -c 'ls /var/run/secrets/kubernetes.io/serviceaccount 2>&1 || true')
  check "$d has no service-account token ($out)" "$(echo "$out" | grep -q -i 'no such file'; echo $?)"
done

echo "== 6. Processes on the node: which container processes run as root"
minikube -p "$PROFILE" ssh -- "ps -eo uid=,comm=" | tr -d '\r' \
  | awk '$2 ~ /^(java|postgres|nginx|mailpit|prometheus|alertmanage|grafana|tempo|loki|otelcol-contri)$/' \
  | sort | uniq -c | sort -k2 -n
root=$(minikube -p "$PROFILE" ssh -- "ps -eo uid=,comm=" | tr -d '\r' \
  | awk '$1 == 0 && $2 ~ /^(java|postgres|nginx|mailpit|prometheus|alertmanage|grafana|tempo|loki)$/' | wc -l)
check "no application process runs as root on the node (otel-agent excepted; found $root)" "$([ "$root" -eq 0 ]; echo $?)"

echo
echo "passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
