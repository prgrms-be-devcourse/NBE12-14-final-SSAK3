"""Run the room-end k6 scenario on a disposable local stack."""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from contextlib import ExitStack, closing
from datetime import datetime
from http.client import HTTPConnection, HTTPException
from pathlib import Path
from threading import Thread

from report_mock import ReportMock


HERE = Path(__file__).resolve().parent
BACKEND = HERE.parents[1] / "backend"
RESULTS = BACKEND / "build" / "reports" / "report-end"


def start_container(stack, name, port, *options):
    # Docker assigns a free host port. Only the container ID created here is removed.
    container = subprocess.check_output(
        ["docker", "create", "--name", name, "-p", f"127.0.0.1::{port}", *options],
        text=True,
    ).strip()
    stack.callback(subprocess.run, ["docker", "rm", "-fv", container], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["docker", "start", container], check=True, stdout=subprocess.DEVNULL)
    binding = subprocess.check_output(["docker", "port", container, f"{port}/tcp"], text=True).strip()
    return binding.rsplit(":", 1)[1]


def wait_for_mysql(container):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        # During initialization MySQL opens a socket before it starts accepting TCP.
        probe = subprocess.run(
            ["docker", "exec", "-e", "MYSQL_PWD=local-only-report", container,
             "mysql", "--protocol=TCP", "-h127.0.0.1", "--connect-timeout=2", "-uroot",
             "-N", "report_load", "-e", "SELECT 1"],
            capture_output=True,
        )
        if probe.returncode == 0 and probe.stdout.strip() == b"1":
            return
        time.sleep(1)
    raise RuntimeError("테스트용 MySQL이 120초 안에 준비되지 않았습니다.")


def stop_backend(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def start_backend(stack, env, runtime, log_path):
    ready = runtime / "backend.port"
    java = (runtime / "java-executable.txt").read_text(encoding="utf-8")
    log = stack.enter_context(log_path.open("ab"))
    process = subprocess.Popen(
        [java, "-jar", "report-load.jar"], cwd=runtime, stdout=log, stderr=subprocess.STDOUT,
        env={**env, "REPORT_LOAD_READY_FILE": str(ready)},
    )
    stack.callback(stop_backend, process)
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"테스트 서버가 시작 중 종료됐습니다. {log_path}")
        if ready.is_file():
            return str(int(ready.read_text(encoding="ascii").strip()))
        time.sleep(0.5)
    raise RuntimeError(f"테스트 서버가 120초 안에 준비되지 않았습니다. {log_path}")


def remote_write_enabled():
    try:
        with closing(HTTPConnection("127.0.0.1", 9090, timeout=1)) as prometheus:
            prometheus.request("GET", "/api/v1/status/flags")
            response = prometheus.getresponse()
            if response.status != 200:
                return False
            flags = json.loads(response.read(65536))["data"]
            return flags["web.enable-remote-write-receiver"] == "true"
    except (OSError, HTTPException, ValueError, KeyError, TypeError):
        return False


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
        command.extend(["-o", "experimental-prometheus-rw", "--tag", f"run_id={output.name}",
                        "--tag", f"rooms={rooms}", "--tag", "phase=measured"])
        k6_env.update({
            "K6_PROMETHEUS_RW_SERVER_URL": "http://127.0.0.1:9090/api/v1/write",
            "K6_PROMETHEUS_RW_TREND_STATS": "p(95),max",
            "K6_PROMETHEUS_RW_PUSH_INTERVAL": "1s",
            "K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM": "false",
            "K6_FEATURES": ",".join(flag for flag in k6_env.get("K6_FEATURES", "").split(",")
                                    if flag.strip() != "native-histograms"),
        })
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
    for tool in ("docker", "k6"):
        if not shutil.which(tool):
            raise RuntimeError(f"PATH에서 {tool} 실행 파일을 찾을 수 없습니다.")
    if subprocess.run(["docker", "info", "--format", "{{.ServerVersion}}"], capture_output=True).returncode:
        raise RuntimeError("Docker 엔진에 연결할 수 없습니다. Docker를 시작한 뒤 다시 실행하세요.")
    run_id = f"{datetime.now():%Y%m%d-%H%M%S}-{uuid.uuid4().hex[:10]}"
    output = RESULTS / run_id
    output.mkdir(parents=True)
    (output / "run.json").write_text(json.dumps({"rooms": rooms}) + "\n", encoding="utf-8")
    print(f"결과 폴더: {output}", flush=True)
    # Keep local account credentials and machine-specific launch files out of the saved results.
    with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
        runtime = Path(directory).resolve()
        log_path = output / "backend.log"
        gradle = BACKEND / ("gradlew.bat" if os.name == "nt" else "gradlew")
        print("테스트 서버 빌드 중…", flush=True)
        with log_path.open("wb") as log:
            build = subprocess.run(
                [str(gradle), "--console=plain", f"-PreportLoadDir={runtime}", "prepareReportLoadLauncher"],
                cwd=BACKEND, stdout=log, stderr=subprocess.STDOUT,
            )
        if build.returncode:
            raise RuntimeError(f"테스트 서버 빌드에 실패했습니다. {log_path}")

        print("전용 MySQL·Redis와 모의 OpenAI 시작 중…", flush=True)
        mysql_name = f"react-report-load-mysql-{run_id}"
        mysql_port = start_container(stack, mysql_name, 3306, "-e", "MYSQL_ROOT_PASSWORD=local-only-report",
                                     "-e", "MYSQL_DATABASE=report_load", "-e", "TZ=Asia/Seoul", "mysql:8.4",
                                     "--default-time-zone=+09:00", "--character-set-server=utf8mb4",
                                     "--collation-server=utf8mb4_unicode_ci")
        redis_port = start_container(stack, f"react-report-load-redis-{run_id}", 6379, "redis:7.4-alpine")
        wait_for_mysql(mysql_name)
        mock = stack.enter_context(ReportMock(0, 1000, quiet=True))
        thread = Thread(target=mock.serve_forever, daemon=True)
        thread.start()
        stack.callback(thread.join)
        stack.callback(mock.shutdown)

        env = {**os.environ, "REPORT_LOAD_MYSQL_PORT": mysql_port, "REPORT_LOAD_REDIS_PORT": redis_port,
               "REPORT_LOAD_MYSQL_CONTAINER": mysql_name, "REPORT_LOAD_MOCK_PORT": str(mock.server_port)}
        env["REPORT_LOAD_BACKEND_PORT"] = start_backend(stack, env, runtime, log_path)
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
