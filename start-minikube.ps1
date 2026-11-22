# Starts the already-deployed VoltStore stack in minikube (profile voltstore-np) and opens the UIs.
# No image builds: for a fresh cluster or new code, follow k8s.txt. Ctrl+C closes the port-forwards.
$ErrorActionPreference = 'Stop'
$profileName = 'voltstore-np'
$ns = 'voltstore'

# The default "minikube" profile holds stale images and competes for Docker Desktop's memory.
if ((minikube status -p minikube --format '{{.Host}}' 2>$null) -eq 'Running') {
    Write-Host 'Stopping the other profile "minikube" to free memory...'
    minikube stop -p minikube | Out-Null
}

Write-Host "Starting minikube profile $profileName..."
minikube start -p $profileName
if ($LASTEXITCODE -ne 0) { throw 'minikube start failed' }

Write-Host 'Waiting for all pods to be Ready (up to 7 min)...'
kubectl --context $profileName wait -n $ns --for=condition=Ready pod --all --timeout=420s
if ($LASTEXITCODE -ne 0) {
    kubectl --context $profileName get pods -n $ns
    throw 'Some pods are not Ready (see above)'
}

# local port -> service:port; 8080 on this machine is taken by a Tomcat service
$forwards = [ordered]@{
    'Frontend'     = '8088 frontend 8080'
    'API Gateway'  = '18081 api-gateway 8080'
    'Grafana'      = '3000 grafana 3000'
    'Tempo'        = '13200 tempo 3200'
    'Prometheus'   = '9090 prometheus 9090'
    'Alertmanager' = '9093 alertmanager 9093'
    'Mailpit'      = '8025 mailpit 8025'
}
$procs = foreach ($f in $forwards.GetEnumerator()) {
    $local, $svc, $remote = $f.Value -split ' '
    Start-Process kubectl -NoNewWindow -PassThru -RedirectStandardOutput "$env:TEMP\pf-$svc.log" `
        -ArgumentList '--context', $profileName, 'port-forward', '-n', $ns, "svc/$svc", "${local}:$remote"
}

Write-Host "`nVoltStore is up:"
foreach ($f in $forwards.GetEnumerator()) { Write-Host ("  {0,-13} http://localhost:{1}" -f $f.Key, ($f.Value -split ' ')[0]) }
Write-Host "`nGrafana login: kubectl get secret -n $ns grafana-admin -o jsonpath='{.data}' (base64)"
Write-Host 'Demo trace: sh docs/reports/tracing-logging/e2e-trace.sh'
Write-Host 'Press Ctrl+C to stop the port-forwards (the cluster keeps running; "minikube stop -p voltstore-np" stops it).'
Start-Process 'http://localhost:3000'

try { Wait-Process -Id $procs.Id }
finally { $procs | Stop-Process -ErrorAction SilentlyContinue }
