#!/bin/sh
# Builds the submission archive of the Kubernetes security assignment
# (docs/tasks/infrastructure/k8s-security-hardening.md). It packs committed files only (git archive of HEAD), so
# ignored files such as k8s/**/secret.yaml and .env cannot get in; it then lists the archive and fails if a secret
# file is inside anyway. Commit first: uncommitted changes are not packed. Usage, from anywhere in the repository:
#   sh docs/reports/k8s-security/build-archive.sh [output.zip]     (default: k8s-security-submission.zip)
set -e
cd "$(git rev-parse --show-toplevel)"
OUT=${1:-k8s-security-submission.zip}

# All manifests (microservices, databases, Ingress, network policies, Kafka, monitoring, observability), the run
# guide, the report with its tests, and the task
git archive --format=zip -o "$OUT" HEAD -- \
  k8s \
  k8s.txt \
  docs/reports/k8s-security.md \
  docs/reports/k8s-security \
  docs/tasks/infrastructure/k8s-security-hardening.md

# Only templates may be inside (*-secret-template.yaml); a real secret.yaml or .env must never be
if unzip -Z1 "$OUT" | grep -E '(^|/)(secret\.yaml|\.env)$'; then
  echo "refusing: secret file in $OUT" >&2
  rm -f "$OUT"
  exit 1
fi
echo "$OUT: $(unzip -Z1 "$OUT" | grep -vc '/$') files, no secret.yaml or .env"
