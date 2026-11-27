"""Measure simultaneous STT backlog uploads on a disposable local stack."""

import argparse
import json
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime
from pathlib import Path

from load_stack import BACKEND, configure_remote_write, load_environment, remote_write_enabled


HERE = Path(__file__).resolve().parent
RESULTS = BACKEND / "build" / "reports" / "stt-reconnect"


def positive_int(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("1 이상의 정수를 입력하세요.")
    return number


def measure(rooms, backlog, offline_seconds, env, fixture, output):
    fixture_cli = [sys.executable, "-B", str(HERE / "stt_fixture.py")]
    print(f"강사 {rooms}명의 로그인 및 STT 세션 준비 중…", flush=True)
    subprocess.run([
        *fixture_cli, "prepare", "--rooms", str(rooms), "--backlog", str(backlog),
        "--offline-seconds", str(offline_seconds), "--fixture", str(fixture),
    ], env=env, check=True)

    summary = output / "k6-summary.json"
    command = [
        "k6", "run", "--quiet", "--summary-export", str(summary),
        "-e", f"BASE_URL=http://127.0.0.1:{env['REPORT_LOAD_BACKEND_PORT']}/api/v1",
        "-e", f"STT_FIXTURE={fixture.as_posix()}",
        "--tag", f"run_id={output.name}", "--tag", f"rooms={rooms}", "--tag", f"backlog={backlog}",
    ]
    k6_env = env.copy()
    if not k6_env.get("K6_OUT") and remote_write_enabled():
        configure_remote_write(command, k6_env, trend_stats="p(95),p(99),max")
        print(f"Grafana Explore에서 볼 실행 ID: {output.name}", flush=True)
    command.append(str(HERE / "stt-reconnect.js"))

    print(f"{rooms}개 방 재연결 측정 중 (방마다 밀린 발언 {backlog}건)…", flush=True)
    with (output / "k6.log").open("wb") as log:
        k6 = subprocess.run(command, env=k6_env, stdout=log, stderr=subprocess.STDOUT)

    # Inspect the database even when a k6 threshold failed.
    verification = output / "db-verification.json"
    db = subprocess.run([*fixture_cli, "verify", "--fixture", str(fixture),
                         "--output", str(verification)], env=env)
    if k6.returncode or db.returncode:
        raise RuntimeError(f"검증 실패 (k6={k6.returncode}, DB={db.returncode}). 결과 폴더: {output}")
    metrics = json.loads(summary.read_text(encoding="utf-8"))["metrics"]
    verified = json.loads(verification.read_text(encoding="utf-8"))["verified"]
    save_ms = metrics["stt_segment_save_ms"]["p(95)"]
    drain = metrics["stt_backlog_drain_ms"]
    print(f"DB 검증 {verified}/{rooms}개 방 | 발언 1건 저장 p95 {save_ms:.1f}ms"
          f" | 방별 재전송 완료 p95 {drain['p(95)'] / 1000:.2f}초, 최대 {drain['max'] / 1000:.2f}초", flush=True)


def run(rooms, backlog, offline_seconds):
    run_id = f"{datetime.now():%Y%m%d-%H%M%S}-{uuid.uuid4().hex[:10]}"
    output = RESULTS / run_id
    output.mkdir(parents=True)
    parameters = {"rooms": rooms, "backlog": backlog, "offlineSeconds": offline_seconds}
    (output / "run.json").write_text(json.dumps(parameters) + "\n", encoding="utf-8")
    print(f"결과 폴더: {output}", flush=True)
    with load_environment(output, run_id) as env, tempfile.TemporaryDirectory() as directory:
        fixture = Path(directory).resolve() / "rooms.json"
        measure(rooms, backlog, offline_seconds, env, fixture, output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="여러 강사의 STT 재연결과 밀린 발언 저장을 측정합니다.")
    parser.add_argument("rooms", type=positive_int, help="동시에 재연결할 강연방 수")
    parser.add_argument("--backlog", type=positive_int, default=20, help="방마다 밀린 발언 수 (기본 20건)")
    parser.add_argument("--offline-seconds", type=positive_int, default=10, help="전송이 끊긴 시간 (기본 10초)")
    args = parser.parse_args()
    try:
        run(args.rooms, args.backlog, args.offline_seconds)
    except KeyboardInterrupt:
        sys.exit(130)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        sys.exit(str(error))
