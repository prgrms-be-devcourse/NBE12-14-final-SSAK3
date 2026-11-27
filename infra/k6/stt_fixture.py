"""Prepare authenticated STT sessions and verify the reconnect scenario in its disposable MySQL."""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, ProxyHandler, Request, build_opener

from report_fixture import API, DATABASE_CONTAINER, create_host, query


def isolated_stack():
    if not os.environ.get("REPORT_LOAD_BACKEND_PORT") or not re.fullmatch(
        r"react-report-load-mysql-[A-Za-z0-9_.-]+", DATABASE_CONTAINER
    ):
        raise ValueError("Use the disposable local load runner's backend and MySQL container.")


def server_instant(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("STT server time must include its UTC offset.")
    return parsed


def prepare_room():
    _, credentials = create_host()
    jar = CookieJar()
    opener = build_opener(ProxyHandler({}), HTTPCookieProcessor(jar))

    def request(path, method="GET", body=None, csrf=None):
        headers = {} if csrf is None else {csrf["headerName"]: csrf["token"]}
        payload = None
        if body is not None:
            payload = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        with opener.open(Request(API + path, data=payload, method=method, headers=headers), timeout=15) as response:
            return json.load(response)["data"]

    csrf = request("/auth/csrf")
    login = request("/auth/login", "POST", credentials, csrf)
    if not login.get("authenticated") or login["email"] != credentials["email"]:
        raise RuntimeError("Host login failed.")
    csrf = request("/auth/csrf")
    room_id = request("/rooms", "POST", {"title": "STT reconnect fixture"}, csrf)["roomId"]
    room = request(f"/rooms/{room_id}/start", "POST", csrf=csrf)
    if room["roomId"] != room_id or room["status"] != "LIVE":
        raise RuntimeError("The new lecture room did not start.")
    key = str(uuid.uuid4())
    session = request(f"/rooms/{room_id}/stt/sessions", "POST", {"startKey": key}, csrf)
    observed_at_ms = time.time_ns() // 1_000_000
    if session["roomId"] != room_id or session["status"] != "RECORDING" or not session["canRecord"]:
        raise RuntimeError("The owner did not acquire the STT session.")
    server_time = session["serverTime"]
    return {"roomId": room_id, "sessionId": session["sttSessionId"], "startKey": key,
            "serverTime": server_time, "clockOffsetMs": round(server_instant(server_time).timestamp() * 1000) - observed_at_ms,
            "cookies": {c.name: c.value for c in jar}, "csrf": csrf, "email": login["email"]}


def prepare(args):
    if min(args.rooms, args.backlog, args.offline_seconds) < 1:
        raise ValueError("Room count, backlog and offline duration must be positive.")
    # Reserve a private session manifest before creating any accounts or rooms.
    with open(args.fixture, "x", encoding="utf-8", opener=private_open) as output:
        manifest = {"rooms": [prepare_room() for _ in range(args.rooms)],
                    "backlog": args.backlog, "offlineSeconds": args.offline_seconds}
        json.dump(manifest, output, ensure_ascii=True)
    print(f"Prepared {args.rooms} LIVE rooms with distinct owners and recording sessions.")


def private_open(path, flags):
    return os.open(path, flags, 0o600)


class RoomCheck:
    def __init__(self, room, ends):
        self.room = room
        self.ends = ends
        self.live_time = round(server_instant(room["serverTime"]).timestamp() * 1000)
        self.sessions = self.gaps = self.live = self.backlog = self.race = self.stored = 0
        self.bad_text = self.bad_times = 0
        self.room_end = self.gap_start = self.anchor = None
        self.failed = set()

    def check_session(self, email, status, ended, sid, start_key, session_state, stopped):
        self.sessions += int(sid is not None)
        self.room_end = ended
        if email != self.room["email"]:
            self.failed.add("roomOwner")
        if sid != self.room["sessionId"] or start_key != self.room["startKey"]:
            self.failed.add("sessionOwner")
        if self.ends:
            valid = status == "ENDED" and session_state == "STOPPED" and ended is not None and ended == stopped
        else:
            valid = status == "LIVE" and session_state == "RECORDING" and ended is None and stopped is None
        if not valid:
            self.failed.add("lifecycle")

    def check_gap(self, sid, key, reason, start, end):
        self.gaps += 1
        self.gap_start = start
        if (not self.ends or sid != self.room["sessionId"] or key != "end-gap" or reason != "ERROR"
                or end is None or start > end or end != self.room_end):
            self.failed.add("gapClosure")

    def check_segment(self, key, text_ok, start, end, received, count, window_ms):
        self.stored += 1
        self.bad_text += int(not text_ok)
        if key == "live-1":
            self.live += 1
            valid_times = start == end == self.live_time
        elif key == "end-race" and self.ends:
            self.race += 1
            valid_times = start == end == self.gap_start
        elif re.fullmatch(r"backlog-[1-9][0-9]*", key):
            ordinal = int(key[8:])
            self.backlog += 1
            # One client sends FIFO. Counting ordinals in insert order catches skips and duplicates.
            if ordinal != self.backlog or ordinal > count:
                self.failed.add("backlogKeys")
            offset = (ordinal - 1) * window_ms // count
            if self.anchor is None:
                self.anchor = start - offset
            valid_times = (self.anchor >= self.live_time and start == self.anchor + offset
                           and end == self.anchor + ordinal * window_ms // count
                           and received + 1000 >= self.anchor + window_ms)  # received_at has second precision.
        else:
            self.failed.add("unexpectedKey")
            return
        self.bad_times += int(not valid_times)

    def finish(self, count):
        if self.sessions != 1:
            self.failed.add("sessionCount")
        if self.gaps != int(self.ends):
            self.failed.add("gapCount")
        if self.live != 1 or self.backlog != count or self.race > int(self.ends):
            self.failed.add("segmentCount")
        if self.bad_text:
            self.failed.add("transcripts")
        if self.bad_times:
            self.failed.add("captureTimes")


def epoch_ms(column):
    # Flyway's STT and room DATETIME columns use a fixed +09:00 clock.
    return f"TIMESTAMPDIFF(MICROSECOND, '1970-01-01 09:00:00', {column}) DIV 1000"


def db_rows(select):
    # JSON_ARRAY keeps a malformed text key or a SQL NULL in its original column.
    return (json.loads(row) for row in query(select))


def verify(args):
    manifest = json.loads(args.fixture.read_bytes())
    rooms = manifest["rooms"]
    count, offline = manifest["backlog"], manifest["offlineSeconds"]
    if not rooms or any(type(n) is not int or n < 1 for n in (count, offline)):
        raise ValueError("Expected rooms, a positive backlog and a positive offline duration.")
    for field in ("roomId", "sessionId"):
        ids = [r[field] for r in rooms]
        if any(type(n) is not int or n < 1 for n in ids) or len(set(ids)) != len(ids):
            raise ValueError("Fixture room and session IDs must be distinct positive integers.")
    if len({r["email"] for r in rooms}) != len(rooms):
        raise ValueError("Expected one distinct host per room.")
    checks = {r["roomId"]: RoomCheck(r, i == 0) for i, r in enumerate(rooms)}
    room_ids = ",".join(str(n) for n in checks)
    session_ids = ",".join(str(r["sessionId"]) for r in rooms)

    for rid, *row in db_rows(f"""
        SELECT JSON_ARRAY(r.id, u.email, r.status, {epoch_ms('r.ended_at')},
                          s.id, s.start_key, s.status, {epoch_ms('s.ended_at')})
        FROM lecture_rooms r JOIN users u ON u.id = r.host_user_id
        LEFT JOIN stt_sessions s ON s.lecture_room_id = r.id WHERE r.id IN ({room_ids});
    """):
        checks[rid].check_session(*row)
    for rid, *row in db_rows(f"""
        SELECT JSON_ARRAY(s.lecture_room_id, s.id, g.event_key, g.reason,
                          {epoch_ms('g.started_at')}, {epoch_ms('g.ended_at')})
        FROM stt_gaps g JOIN stt_sessions s ON s.id = g.stt_session_id WHERE s.lecture_room_id IN ({room_ids});
    """):
        checks[rid].check_gap(*row)

    by_session = {r["sessionId"]: checks[r["roomId"]] for r in rooms}
    last_id = 0
    while True:
        # Bound the volume returned by the mysql CLI even for large reconnect backlogs.
        rows = query(f"""
            SELECT JSON_ARRAY(g.id, g.stt_session_id, g.segment_key,
                       BINARY g.transcript = BINARY CONCAT('room ', s.lecture_room_id, ' / ', g.segment_key),
                       {epoch_ms('g.segment_start_at')}, {epoch_ms('g.segment_end_at')}, {epoch_ms('g.received_at')})
            FROM stt_segments g JOIN stt_sessions s ON s.id = g.stt_session_id
            WHERE g.id > {last_id} AND g.stt_session_id IN ({session_ids}) ORDER BY g.id LIMIT 2048;
        """)
        for record in rows:
            last_id, sid, *event = json.loads(record)
            by_session[sid].check_segment(*event, count, offline * 1000)
        if len(rows) < 2048:
            break

    summary = {"rooms": len(rooms), "backlog": count, "expectedSegments": len(rooms) * (count + 1),
               "storedSegments": 0, "endRaceSegments": 0, "verified": 0, "failures": []}
    for room_id, room in checks.items():
        room.finish(count)
        summary["storedSegments"] += room.stored
        summary["endRaceSegments"] += room.race
        if room.failed:
            summary["failures"].append({"roomId": room_id, "failedChecks": sorted(room.failed),
                                        "stored": room.stored, "wrongText": room.bad_text, "wrongTimes": room.bad_times})
        else:
            summary["verified"] += 1
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {summary['verified']}/{len(rooms)} rooms.")
    return not summary["failures"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    seed = commands.add_parser("prepare")
    seed.add_argument("--rooms", type=int, required=True)
    seed.add_argument("--backlog", type=int, required=True)
    seed.add_argument("--offline-seconds", type=int, required=True)
    seed.add_argument("--fixture", type=Path, required=True)
    check = commands.add_parser("verify")
    check.add_argument("--fixture", type=Path, required=True)
    check.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    isolated_stack()
    if args.command == "prepare":
        prepare(args)
        return 0
    return 0 if verify(args) else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except HTTPError as error:
        sys.exit(f"Fixture API returned HTTP {error.code}.")
    except (OSError, RuntimeError, ValueError, KeyError, subprocess.SubprocessError) as error:
        sys.exit(str(error))
