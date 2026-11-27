"""Prepare and verify fresh lecture rooms in the isolated local report-load stack."""

import argparse
import json
import os
import subprocess
import sys
import uuid
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener


def local_port(name, default):
    value = os.environ.get(name, str(default))
    if not value.isascii() or not value.isdecimal() or not 1 <= int(value) <= 65535:
        raise SystemExit(f"{name} must be a TCP port between 1 and 65535")
    return int(value)


API = f"http://127.0.0.1:{local_port('REPORT_LOAD_BACKEND_PORT', 18640)}/api/v1"
STATS = f"http://127.0.0.1:{local_port('REPORT_LOAD_MOCK_PORT', 18641)}/stats"
DATABASE_CONTAINER = os.environ.get("REPORT_LOAD_MYSQL_CONTAINER", "react-report-load-mysql")


def query(sql):
    result = subprocess.run(
        ["docker", "exec", "-i", "--", DATABASE_CONTAINER, "sh", "-ec",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 '
         '--batch --raw --skip-column-names --user=root --database=report_load'],
        input=sql, capture_output=True, encoding="utf-8", timeout=60,
    )
    if result.returncode:
        raise RuntimeError(f"MySQL fixture query failed: {result.stderr.strip()}")
    return result.stdout.splitlines()


def create_host():
    email = f"report-load-{uuid.uuid4().hex}@example.test"
    password = f"Load-{uuid.uuid4().hex}"
    opener = build_opener(ProxyHandler({}), HTTPCookieProcessor(CookieJar()))
    with opener.open(f"{API}/auth/csrf", timeout=10) as response:
        csrf = json.load(response)["data"]
    request = Request(
        f"{API}/auth/signup",
        data=json.dumps({"email": email, "password": password, "name": "Load Test Host"}).encode(),
        headers={"Content-Type": "application/json", csrf["headerName"]: csrf["token"]},
    )
    with opener.open(request, timeout=15) as response:
        user_id = int(json.load(response)["data"]["userId"])
    return user_id, {"email": email, "password": password}


def room_records(host_id):
    # Seed one 30-second window already analyzed before the measured lecture end.
    # The real finalizer processes the remaining empty windows; Gemini is unnecessary.
    return f"""
        INSERT INTO lecture_rooms(host_user_id, title, join_code, status, created_at, started_at)
        VALUES ({int(host_id)}, 'Report load fixture', '{uuid.uuid4().hex[:12]}',
                'LIVE', @start, @start);
        SET @room = LAST_INSERT_ID();
        INSERT INTO lecture_participants(lecture_room_id, nickname, participant_token_hash, joined_at)
        VALUES (@room, 'Test audience', SHA2(UUID(), 256), @start);
        SET @participant = LAST_INSERT_ID();
        INSERT INTO chat_messages(lecture_room_id, participant_id, content, received_at, client_message_id)
        VALUES (@room, @participant, CONCAT('How is evidence stored in room ', @room, '?'), @spoken, UUID());
        SET @message = LAST_INSERT_ID();
        INSERT INTO stt_sessions(lecture_room_id, status, started_at, start_key)
        VALUES (@room, 'RECORDING', @start, UUID());
        INSERT INTO stt_segments(stt_session_id, segment_key, transcript, segment_start_at, segment_end_at, received_at)
        VALUES (LAST_INSERT_ID(), UUID(), CONCAT('Room ', @room, ' stores lecture evidence.'), @spoken, @spoken, @spoken);
        INSERT INTO ai_analyses(lecture_room_id, window_start_at, window_end_at, status, created_at, completed_at)
        VALUES (@room, @start, @window_end, 'COMPLETED', @window_end, @window_end);
        INSERT INTO ai_topic_groups(analysis_id, topic, category, summary, created_at)
        VALUES (LAST_INSERT_ID(), 'Evidence storage', 'QUESTION', 'The audience asked about evidence storage.', @window_end);
        INSERT INTO ai_topic_group_messages(topic_group_id, chat_message_id) VALUES (LAST_INSERT_ID(), @message);
        SELECT @room;
    """


def prepare(path, count):
    if count <= 0:
        raise ValueError("--rooms must be positive")
    if path.exists():
        raise ValueError(f"Fixture file already exists: {path}")
    if not path.parent.is_dir():
        raise ValueError(f"Fixture directory does not exist: {path.parent}")
    hosts = [create_host() for _ in range(count)]
    sql = """
        START TRANSACTION;
        SET @start = UTC_TIMESTAMP() + INTERVAL 9 HOUR - INTERVAL 35 SECOND;
        SET @spoken = @start + INTERVAL 3 SECOND;
        SET @window_end = @start + INTERVAL 30 SECOND;
    """
    room_ids = query(sql + "".join(room_records(user_id) for user_id, _ in hosts) + "COMMIT;")
    rooms = [{"roomId": int(room_id), **credentials}
             for room_id, (_, credentials) in zip(room_ids, hosts, strict=True)]
    with path.open("x", encoding="utf-8") as output:
        json.dump({"rooms": rooms}, output, indent=2)
    print(f"Prepared {len(rooms)} LIVE rooms in {path}")


def verify(path):
    rooms = json.loads(path.read_text(encoding="utf-8"))["rooms"]
    ids = [int(room["roomId"]) for room in rooms]
    if not ids or len(ids) != len(set(ids)) or min(ids) < 1:
        raise ValueError("Fixture must contain distinct, positive room IDs")
    # Check the persisted payload, including the original evidence for this specific room.
    rows = query(f"""
        SELECT r.id, r.status, COUNT(p.id), COALESCE(GROUP_CONCAT(p.status), 'MISSING'),
               COUNT(CASE WHEN p.status = 'COMPLETED' AND p.generated_at IS NOT NULL
                   AND JSON_SEARCH(p.content, 'one', CONCAT('Room ', r.id, ' stores lecture evidence.'),
                                   NULL, '$.timeline[*].sttSegments[*].transcript') IS NOT NULL
                   AND JSON_SEARCH(p.content, 'one', CONCAT('How is evidence stored in room ', r.id, '?'),
                                   NULL, '$.timeline[*].chatMessages[*].text') IS NOT NULL THEN 1 END),
               (SELECT COUNT(*) FROM stt_sessions s WHERE s.lecture_room_id = r.id
                   AND s.status = 'STOPPED' AND s.ended_at IS NOT NULL),
               TIMESTAMPDIFF(SECOND, r.ended_at, MAX(f.completed_at)),
               TIMESTAMPDIFF(SECOND, MAX(f.completed_at), MAX(p.generated_at)),
               TIMESTAMPDIFF(SECOND, r.ended_at, MAX(p.generated_at))
        FROM lecture_rooms r LEFT JOIN lecture_reports p ON p.lecture_room_id = r.id
        LEFT JOIN ai_analysis_finalizations f ON f.lecture_room_id = r.id AND f.status = 'COMPLETED'
        WHERE r.id IN ({",".join(map(str, ids))}) GROUP BY r.id, r.status, r.ended_at ORDER BY r.id;
    """)
    opener = build_opener(ProxyHandler({}))
    with opener.open(STATS, timeout=10) as response:
        by_room = json.load(response)["byRoom"]
    seen = set()
    failures = []
    phase_samples = {"endToAnalysisFinalized": [], "analysisFinalizedToReportGenerated": [],
                     "endToReportGenerated": []}
    for row in rows:
        key, status, reports, report_status, complete, stopped, *timings = row.split("\t")
        seen.add(int(key))
        # MySQL DATETIME columns here have second precision; missing endpoints produce NULL.
        for values, seconds in zip(phase_samples.values(), timings, strict=True):
            if seconds != "NULL":
                values.append(int(seconds))
        finalized = timings[0] != "NULL"
        creates = by_room.get(key, 0)
        if (status != "ENDED" or reports != "1" or complete != "1" or stopped != "1"
                or creates != 1 or not finalized):
            failures.append({"roomId": int(key), "status": status, "reports": int(reports),
                             "reportStatus": report_status, "completedWithEvidence": int(complete),
                             "closedStt": int(stopped), "providerCreates": creates, "analysisFinalized": finalized})
    failures.extend({"roomId": key, "status": "MISSING"} for key in sorted(set(ids) - seen))
    phases = {}
    for name, values in phase_samples.items():
        values.sort()
        size = len(values)
        # Nearest-rank p95, including small fixtures where it coincides with the maximum.
        phases[name] = {"samples": size, "min": values[0] if size else None,
                        "p95": values[(95 * size - 1) // 100] if size else None,
                        "max": values[-1] if size else None}
    print(json.dumps({"rooms": len(ids), "verified": len(ids) - len(failures),
                      "failures": failures, "dbPhaseSeconds": phases}, ensure_ascii=True))
    return not failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    seed = actions.add_parser("prepare")
    seed.add_argument("--rooms", type=int, required=True)
    seed.add_argument("--file", type=Path, required=True)
    check = actions.add_parser("verify")
    check.add_argument("--file", type=Path, required=True)
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.file, args.rooms)
        return 0
    return 0 if verify(args.file) else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except HTTPError as error:
        sys.exit(f"HTTP {error.code} from {error.url}: {error.read(1024).decode(errors='replace')}")
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        sys.exit(str(error))
