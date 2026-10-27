#!/bin/sh
# Builds the submission archive of the tracing and logging assignment (docs/tasks/infrastructure/observability/08).
# It packs committed files only (git archive of HEAD), so ignored files such as k8s/**/secret.yaml and .env cannot
# get in; it then lists the archive and fails if a secret file is inside anyway. Commit first: uncommitted changes
# are not packed. Usage, from anywhere in the repository:
#   sh docs/reports/tracing-logging/build-archive.sh [output.zip]     (default: tracing-logging-submission.zip)
set -e
cd "$(git rev-parse --show-toplevel)"
OUT=${1:-tracing-logging-submission.zip}

git archive --format=zip -o "$OUT" HEAD -- \
  docs/reports/tracing-logging.md \
  docs/reports/tracing-logging \
  docs/tasks/infrastructure/observability \
  k8s/observability \
  k8s/network/network-policies.yaml \
  'k8s/*/app-config.yaml' \
  '*/pom.xml' \
  '*/src/main/resources/application.yml' \
  '*/src/main/resources/db/migration/*__add_outbox_traceparent.sql' \
  '*/config/TracingConfig.java' \
  '*/clients/ClientsConfiguration.java' \
  '*/outbox/*.java' \
  '*/config/KafkaConfig.java'

# Only templates may be inside (*-secret-template.yaml); a real secret.yaml or .env must never be
if unzip -Z1 "$OUT" | grep -E '(^|/)(secret\.yaml|\.env)$'; then
  echo "refusing: secret file in $OUT" >&2
  rm -f "$OUT"
  exit 1
fi
echo "$OUT: $(unzip -Z1 "$OUT" | grep -vc '/$') files, no secret.yaml or .env"
