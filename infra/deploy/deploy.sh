#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# SCRUM-88: GitHub Actions -> SSM -> EC2 운영 배포
# 실행: bash /opt/react/.staging/<SHA>.deploy.sh <SHA>
# 운영 .env와 root의 GHCR 로그인은 EC2에 미리 준비한다.

DEPLOY_DIR="/opt/react"
COMPOSE_FILE="$DEPLOY_DIR/compose.prod.yaml"
SCRIPT_FILE="$DEPLOY_DIR/deploy.sh"
ENV_FILE="$DEPLOY_DIR/.env"
STATE_FILE="$DEPLOY_DIR/current-image-tag"
STAGING_DIR="$DEPLOY_DIR/.staging"
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

NEW_TAG="$1"
NEW_COMPOSE="$STAGING_DIR/$NEW_TAG.compose.prod.yaml"
NEW_SCRIPT="$STAGING_DIR/$NEW_TAG.deploy.sh"

if [[ ! -s "$NEW_COMPOSE" || ! -s "$NEW_SCRIPT" ]]; then
  echo "SSM으로 전달한 새 배포 파일이 없습니다." >&2
  exit 1
fi
if [[ ! -s "$ENV_FILE" ]]; then
  echo "운영 환경변수 파일이 없습니다: $ENV_FILE" >&2
  exit 1
fi
if [[ ! -s "$DOCKER_CONFIG/config.json" ]]; then
  echo "GHCR 인증 설정이 없습니다. root 계정으로 docker login ghcr.io를 수행하세요." >&2
  exit 1
fi
bash -n "$NEW_SCRIPT"

mkdir -p "$DEPLOY_DIR"
exec 9>"$DEPLOY_DIR/.deploy.lock"
flock -n 9 || {
  echo "이미 다른 배포가 진행 중입니다." >&2
  exit 1
}

PREVIOUS_TAG=""
if [[ -f "$STATE_FILE" ]]; then
  IFS= read -r PREVIOUS_TAG < "$STATE_FILE" || true
  if [[ -n "$PREVIOUS_TAG" && ! "$PREVIOUS_TAG" =~ ^[0-9a-f]{40}$ ]]; then
    echo "기존 이미지 태그 기록이 올바르지 않습니다." >&2
    exit 1
  fi
fi
if [[ -n "$PREVIOUS_TAG" && ! -f "$COMPOSE_FILE" ]]; then
  echo "이전 배포 기록이 있지만 운영 Compose 파일이 없습니다." >&2
  exit 1
fi

# 현재 운영 파일은 새 배포가 성공하기 전까지 유지한다.
BACKUP_DIR="$(mktemp -d "$DEPLOY_DIR/.deploy-backup.XXXXXX")"
HAD_COMPOSE=0
HAD_SCRIPT=0
if [[ -f "$COMPOSE_FILE" ]]; then
  cp -p "$COMPOSE_FILE" "$BACKUP_DIR/compose.prod.yaml"
  HAD_COMPOSE=1
fi
if [[ -f "$SCRIPT_FILE" ]]; then
  cp -p "$SCRIPT_FILE" "$BACKUP_DIR/deploy.sh"
  HAD_SCRIPT=1
fi

SWITCHED=0
ACTIVE_CHANGED=0
NEXT_COMPOSE=""
NEXT_SCRIPT=""
NEXT_STATE=""

cleanup() {
  rm -f "${NEXT_COMPOSE:-}" "${NEXT_SCRIPT:-}" "${NEXT_STATE:-}" || true
  rm -rf "$BACKUP_DIR" || true
}
trap cleanup EXIT

compose_new() {
  docker compose --env-file "$ENV_FILE" -f "$NEW_COMPOSE" "$@"
}
compose_previous() {
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
  set +e
  echo "배포 실패: $NEW_TAG" >&2

  # 이미지/컨테이너뿐 아니라 운영 Compose와 배포 스크립트도 원상복구한다.
  if [[ "$ACTIVE_CHANGED" == 1 ]]; then
    if [[ "$HAD_COMPOSE" == 1 ]]; then
      cp -p "$BACKUP_DIR/compose.prod.yaml" "$COMPOSE_FILE"
    else
      rm -f "$COMPOSE_FILE"
    fi
    if [[ "$HAD_SCRIPT" == 1 ]]; then
      cp -p "$BACKUP_DIR/deploy.sh" "$SCRIPT_FILE"
    else
      rm -f "$SCRIPT_FILE"
    fi
  fi

  if [[ "$SWITCHED" == 1 && -n "$PREVIOUS_TAG" ]]; then
    echo "이전 Compose 및 이미지로 복구를 시도합니다: $PREVIOUS_TAG" >&2
    export IMAGE_TAG="$PREVIOUS_TAG"
    if compose_previous up -d --no-build --wait --wait-timeout 180 && check_health; then
      echo "이전 버전으로 복구했습니다." >&2
    else
      echo "자동 복구 실패: EC2에서 수동 확인이 필요합니다." >&2
    fi
  elif [[ "$SWITCHED" == 1 ]]; then
    echo "첫 배포이므로 복구할 이전 버전이 없습니다." >&2
  fi
  exit "$status"
}
trap 'on_error $?' ERR

# .env는 EC2에 저장된 운영값을 그대로 사용한다.
# SSM 명령에 비밀값을 전달하지 않는다.
export IMAGE_TAG="$NEW_TAG"
compose_new config --quiet
compose_new pull backend web

SWITCHED=1
compose_new up -d --no-build --wait --wait-timeout 180
check_health

# 새 이미지가 정상 동작한 뒤에만 운영 파일과 태그를 확정한다.
NEXT_COMPOSE="$(mktemp "$DEPLOY_DIR/.compose.next.XXXXXX")"
NEXT_SCRIPT="$(mktemp "$DEPLOY_DIR/.script.next.XXXXXX")"
NEXT_STATE="$(mktemp "$DEPLOY_DIR/.state.next.XXXXXX")"
install -m 600 "$NEW_COMPOSE" "$NEXT_COMPOSE"
install -m 700 "$NEW_SCRIPT" "$NEXT_SCRIPT"
printf '%s\n' "$NEW_TAG" > "$NEXT_STATE"

ACTIVE_CHANGED=1
mv -f "$NEXT_COMPOSE" "$COMPOSE_FILE"
mv -f "$NEXT_SCRIPT" "$SCRIPT_FILE"
mv -f "$NEXT_STATE" "$STATE_FILE"

# 원본 스테이징 파일은 성공 후 제거한다.
rm -f "$NEW_COMPOSE" "$NEW_SCRIPT" || true
echo "배포 성공: $NEW_TAG"
