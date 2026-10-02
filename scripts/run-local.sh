#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p .local-data
if [[ ! -f .local-data/operator-token ]]; then
  umask 077
  openssl rand -hex 32 > .local-data/operator-token
fi
if [[ -z "${OPERATOR_TOKEN:-}" ]]; then
  OPERATOR_TOKEN="$(cat .local-data/operator-token)"
  export OPERATOR_TOKEN
fi
if [[ -z "${WORKFLOW_MAVEN_REPOSITORY:-}" && -d .maven-repository ]]; then
  WORKFLOW_MAVEN_REPOSITORY="$(pwd)/.maven-repository"
  export WORKFLOW_MAVEN_REPOSITORY
fi
exec java -jar target/url-shortener-0.1.0.jar "$@"
