#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# SCRUM-88: GitHub Actions -> SSM -> EC2 운영 배포
# 실행: /opt/react/deploy.sh <40자리 Git 커밋 SHA>
# 사전 준비: /opt/react/.env 생성, root 계정으로 GHCR docker login

DEPLOY_DIR="/opt/react"
COMPOSE_FILE="$DEPLOY_DIR/compose.prod.yaml"
ENV_FILE="$DEPLOY_DIR/.env"
STATE_FILE="$DEPLOY_DIR/current-image-tag"

# SSM 명령 실행 환경과 무관하게 root의 GHCR 인증 설정을 사용한다.
export DOCKER_CONFIG="/root/.docker"

if [[ $# -ne 1 || ! "$1" =~ ^[0-9a-f]{40}$ ]]; then
  echo "사용법: $0 <40자리 Git 커밋 SHA>" >&2
  exit 1
fi
if [[ $(id -u) -ne 0 ]]; then
  echo "배포 스크립트는 root 권한으로 실행해야 합니다." >&2
  exit 1
fi
for command in docker curl flock; do
  command -v "$command" >/dev/null || {
    echo "필요한 명령이 없습니다: $command" >&2
    exit 1
  }
done
if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "운영 Compose 파일이 없습니다: $COMPOSE_FILE" >&2
  exit 1
fi
if [[ ! -s "$ENV_FILE" ]]; then
  echo "운영 환경변수 파일이 없습니다: $ENV_FILE" >&2
  exit 1
fi
if [[ ! -f "$DOCKER_CONFIG/config.json" ]]; then
  echo "GHCR 인증 설정이 없습니다. root 계정으로 docker login ghcr.io를 먼저 실행하세요." >&2
  exit 1
fi

mkdir -p "$DEPLOY_DIR"
exec 9>"$DEPLOY_DIR/.deploy.lock"
flock -n 9 || {
  echo "이미 다른 배포가 진행 중입니다." >&2
  exit 1
}

NEW_TAG="$1"
PREVIOUS_TAG=""
SWITCHED=0

if [[ -f "$STATE_FILE" ]]; then
  IFS= read -r PREVIOUS_TAG < "$STATE_FILE" || true
  if [[ -n "$PREVIOUS_TAG" && ! "$PREVIOUS_TAG" =~ ^[0-9a-f]{40}$ ]]; then
    echo "기존 이미지 태그 기록이 올바르지 않습니다." >&2
    exit 1
  fi
fi

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

check_health() {
  for attempt in $(seq 1 30); do
    if curl -fsS --max-time 3 http://127.0.0.1:18080/actuator/health 2>/dev/null \
        | grep -q '"status":"UP"' \
        && curl -fsS --max-time 3 -o /dev/null http://127.0.0.1/ 2>/dev/null; then
      return 0
    fi
    sleep 3
  done
  return 1
}

on_error() {
  local status="$1"
  trap - ERR
  echo "배포 실패: $NEW_TAG" >&2

  if [[ "$SWITCHED" == 1 && -n "$PREVIOUS_TAG" ]]; then
    echo "직전 이미지로 복구를 시도합니다: $PREVIOUS_TAG" >&2
    export IMAGE_TAG="$PREVIOUS_TAG"
    if compose up -d --no-build --wait --wait-timeout 180 && check_health; then
      echo "이전 버전으로 복구했습니다." >&2
    else
      echo "자동 복구도 실패했습니다. EC2를 직접 점검해야 합니다." >&2
    fi
  elif [[ "$SWITCHED" == 1 ]]; then
    echo "첫 배포는 이전 이미지가 없어 자동 복구할 수 없습니다." >&2
  fi
  exit "$status"
}
trap 'on_error $?' ERR

# 환경변수는 EC2에 최초 한 번 설정한 .env 파일을 계속 재사용한다.
# 비밀값을 GitHub Actions나 SSM 명령에 포함하지 않는다.
export IMAGE_TAG="$NEW_TAG"
compose config --quiet

# EC2 root 계정에 저장된 GHCR 인증 정보를 Docker가 사용한다.
# 이미지 다운로드 실패 시 실행 중인 컨테이너는 건드리지 않는다.
compose pull backend web

SWITCHED=1
compose up -d --no-build --wait --wait-timeout 180
check_health

# 정상 배포가 검증된 후에만 현재 이미지 태그를 기록한다.
TMP_STATE="$(mktemp "$DEPLOY_DIR/.current-image-tag.XXXXXX")"
trap 'rm -f "${TMP_STATE:-}"' EXIT
printf '%s\n' "$NEW_TAG" > "$TMP_STATE"
mv -f "$TMP_STATE" "$STATE_FILE"

echo "배포 성공: $NEW_TAG"
