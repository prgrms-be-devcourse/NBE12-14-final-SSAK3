#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# SCRUM-88: GitHub Actions -> SSM -> EC2 운영 배포
# 실행: /opt/react/deploy.sh <40자리 Git 커밋 SHA>

DEPLOY_DIR="/opt/react"
COMPOSE_FILE="$DEPLOY_DIR/compose.prod.yaml"
ENV_FILE="$DEPLOY_DIR/.env"
STATE_FILE="$DEPLOY_DIR/current-image-tag"
AWS_REGION="ap-northeast-2"

if [[ $# -ne 1 || ! "$1" =~ ^[0-9a-f]{40}$ ]]; then
  echo "사용법: $0 <40자리 Git 커밋 SHA>" >&2
  exit 1
fi

if [[ $(id -u) -ne 0 ]]; then
  echo "배포 스크립트는 root 권한으로 실행해야 합니다." >&2
  exit 1
fi

for command in aws docker curl flock; do
  command -v "$command" >/dev/null || {
    echo "필요한 명령이 없습니다: $command" >&2
    exit 1
  }
done

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "운영 Compose 파일이 없습니다: $COMPOSE_FILE" >&2
  exit 1
fi

mkdir -p "$DEPLOY_DIR"

exec 9>"$DEPLOY_DIR/.deploy.lock"
flock -n 9 || {
  echo "이미 다른 배포가 진행 중입니다." >&2
  exit 1
}

WORK_DIR="$(mktemp -d "$DEPLOY_DIR/.deploy.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

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

if [[ -f "$ENV_FILE" ]]; then
  cp -p "$ENV_FILE" "$WORK_DIR/previous.env"
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

  if [[ "$SWITCHED" == 1 && -n "$PREVIOUS_TAG" && -f "$WORK_DIR/previous.env" ]]; then
    echo "직전 이미지로 복구를 시도합니다: $PREVIOUS_TAG" >&2

    cp -p "$WORK_DIR/previous.env" "$ENV_FILE"
    export IMAGE_TAG="$PREVIOUS_TAG"

    if compose up -d --no-build --wait --wait-timeout 180 && check_health; then
      echo "이전 버전으로 복구했습니다." >&2
    else
      echo "자동 복구도 실패했습니다. EC2를 직접 점검해야 합니다." >&2
    fi
  elif [[ "$SWITCHED" == 1 ]]; then
    echo "첫 배포는 이전 앱 이미지가 없어 자동 복구할 수 없습니다." >&2
  fi

  exit "$status"
}

trap 'on_error $?' ERR

# 운영 환경변수는 EC2에서 Parameter Store로부터 직접 읽는다.
aws ssm get-parameter \
  --region "$AWS_REGION" \
  --name "/react/prod/env" \
  --with-decryption \
  --query 'Parameter.Value' \
  --output text > "$WORK_DIR/new.env"

# 이미지 교체 전에 운영 환경변수와 Compose 설정을 검증한다.
export IMAGE_TAG="$NEW_TAG"

docker compose \
  --env-file "$WORK_DIR/new.env" \
  -f "$COMPOSE_FILE" config --quiet

GHCR_USERNAME="$(aws ssm get-parameter \
  --region "$AWS_REGION" \
  --name "/react/prod/ghcr-username" \
  --query 'Parameter.Value' \
  --output text)"

# GHCR 인증 정보는 임시 디렉터리에만 보관한다.
export DOCKER_CONFIG="$WORK_DIR/docker-config"
mkdir -m 700 "$DOCKER_CONFIG"

aws ssm get-parameter \
  --region "$AWS_REGION" \
  --name "/react/prod/ghcr-token" \
  --with-decryption \
  --query 'Parameter.Value' \
  --output text \
  | docker login ghcr.io \
      -u "$GHCR_USERNAME" \
      --password-stdin >/dev/null

# 새 이미지 다운로드를 먼저 완료한다.
docker compose \
  --env-file "$WORK_DIR/new.env" \
  -f "$COMPOSE_FILE" pull backend web

# 다운로드 성공 후 운영 환경변수를 교체한다.
mv "$WORK_DIR/new.env" "$ENV_FILE"
SWITCHED=1

# 기존 컨테이너를 새 이미지로 교체한다.
compose up -d --no-build --wait --wait-timeout 180

# 백엔드 및 프론트엔드 응답 확인
check_health

# 정상 배포한 이미지 태그 기록
printf '%s\n' "$NEW_TAG" > "$WORK_DIR/current-image-tag"
mv "$WORK_DIR/current-image-tag" "$STATE_FILE"

echo "배포 성공: $NEW_TAG"
