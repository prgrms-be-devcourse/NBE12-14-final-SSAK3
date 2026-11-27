"""Run the room-end k6 scenario on a disposable local stack."""

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
RESULTS = BACKEND / "build" / "reports" / "report-end"


def measure(rooms, env, fixture, output, live=False):
    output.mkdir(exist_ok=True)
    fixture_cli = [sys.executable, "-B", str(HERE / "report_fixture.py")]
    subprocess.run([*fixture_cli, "prepare", "--rooms", str(rooms), "--file", str(fixture)],
                   env=env, check=True, stdout=subprocess.DEVNULL)
    summary = output / "k6-summary.json"
    command = [
        "k6", "run", "--quiet", "--summary-export", str(summary),
        "-e", f"FIXTURE_FILE={fixture}", "-e", "POLL_MS=1000",
        "-e", f"BASE_URL=http://127.0.0.1:{env['REPORT_LOAD_BACKEND_PORT']}",
        "-e", f"MOCK_URL=http://127.0.0.1:{env['REPORT_LOAD_MOCK_PORT']}",
    ]
    k6_env = env.copy()
    if live:
        configure_remote_write(command, k6_env)
        command.extend(["--tag", f"run_id={output.name}", "--tag", f"rooms={rooms}", "--tag", "phase=measured"])
    command.append(str(HERE / "report-end.js"))
    with (output / "k6.log").open("wb") as log:
        k6 = subprocess.run(command, env=k6_env, stdout=log, stderr=subprocess.STDOUT)
    verification = output / "db-verification.json"
    with verification.open("wb") as report:
        db = subprocess.run([*fixture_cli, "verify", "--file", str(fixture)], env=env, stdout=report)
    if k6.returncode or db.returncode:
        raise RuntimeError(f"k6 또는 DB 검증 실패 (k6={k6.returncode}, DB={db.returncode}). 결과: {output}")
    metrics = json.loads(summary.read_text(encoding="utf-8"))["metrics"]
    verified = json.loads(verification.read_text(encoding="utf-8"))["verified"]
    end_ms = metrics["room_end_ms"]["p(95)"]
    complete_s = metrics["report_completed_observed_ms"]["p(95)"] / 1000
    print(f"검증 {verified}/{rooms}개 방 | 종료 API p95 {end_ms:.1f}ms | 보고서 완료 확인 p95 {complete_s:.2f}초", flush=True)


def run(rooms):
    run_id = f"{datetime.now():%Y%m%d-%H%M%S}-{uuid.uuid4().hex[:10]}"
    output = RESULTS / run_id
    output.mkdir(parents=True)
    (output / "run.json").write_text(json.dumps({"rooms": rooms}) + "\n", encoding="utf-8")
    print(f"결과 폴더: {output}", flush=True)
    # Keep local account credentials and machine-specific launch files out of the saved results.
    with tempfile.TemporaryDirectory() as directory, load_environment(output, run_id) as env:
        runtime = Path(directory).resolve()
        print("1개 방 예열 중…", flush=True)
        measure(1, env, runtime / "warmup.json", output / "warmup")
        print(f"{rooms}개 방 동시 종료 측정 중…", flush=True)
        # An explicitly configured k6 output takes precedence over local auto-detection.
        live = not env.get("K6_OUT") and remote_write_enabled()
        if live:
            print(f"Grafana: http://localhost:3000/d/react-report-load?var-run_id={run_id}", flush=True)
        measure(rooms, env, runtime / "rooms.json", output, live=live)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="강연방을 동시에 종료하고 보고서 완료까지 측정합니다.")
    parser.add_argument("rooms", type=int, help="동시에 종료할 강연방 수")
    args = parser.parse_args()
    if args.rooms < 1:
        parser.error("강연방 수는 1 이상이어야 합니다.")
    try:
        run(args.rooms)
    except KeyboardInterrupt:
        sys.exit(130)
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        sys.exit(str(error))
