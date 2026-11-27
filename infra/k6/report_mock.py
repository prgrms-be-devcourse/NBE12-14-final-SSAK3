"""Local synchronous Responses API fixture for the room-end load test."""

import argparse
import json
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock


def make_draft(model_input):
    chats = [record for record in model_input["evidenceRecords"] if record["type"] == "CHAT_MESSAGE"]
    if not chats:
        raise ValueError("The fixture must contain an in-window audience chat.")
    first = chats[0]
    interest = {
        "text": "부하 테스트용 청중 의견",
        "evidence": [{"type": "CHAT_MESSAGE", "id": first["id"]}],
        "relatedWindows": first["relatedWindows"],
        "perspectives": [],
    }
    return {
        "overview": {"mainInterests": [interest], "reviewPoints": [], "suggestions": []},
        "followUp": {"reviewedChatIds": [chat["id"] for chat in chats], "groups": []},
    }


class ReportMock(ThreadingHTTPServer):
    def __init__(self, port, delay_ms, quiet=False):
        super().__init__(("127.0.0.1", port), Handler)
        self.delay_seconds = delay_ms / 1000
        self.quiet = quiet
        self.lock = Lock()
        self.requests = 0
        self.by_room = Counter()

    def record_request(self, room_id):
        with self.lock:
            self.requests += 1
            if room_id is not None:
                self.by_room[room_id] += 1
            return self.requests

    def stats(self):
        with self.lock:
            return {"requests": self.requests, "byRoom": dict(self.by_room)}


class Handler(BaseHTTPRequestHandler):
    def log_request(self, code="-", size="-"):
        if not self.server.quiet or code != 200:
            super().log_request(code, size)

    def send_json(self, status, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/stats":
            self.send_json(200, self.server.stats())
        else:
            self.send_error(404)

    def read_body(self):
        # Spring's streaming request factory can send JSON using HTTP/1.1 chunks.
        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0:
                raise ValueError("Expected a JSON request body.")
            return self.rfile.read(length)
        chunks = bytearray()
        while True:
            size = int(self.rfile.readline().partition(b";")[0], 16)
            if size == 0:
                while self.rfile.readline() not in (b"\r\n", b""):
                    pass
                return chunks
            if size < 0:
                raise ValueError("Invalid chunk size.")
            chunks.extend(self.rfile.read(size))
            if self.rfile.read(2) != b"\r\n":
                raise ValueError("Invalid chunk terminator.")

    def do_POST(self):
        if self.path != "/v1/responses":
            self.send_error(404)
            return
        room_id = None
        try:
            payload = json.loads(self.read_body())
            model_input = json.loads(payload["input"])
            room_id = int(model_input["roomId"])
            if payload.get("background") or payload.get("store"):
                raise ValueError("Use synchronous report generation for this test.")
            draft = make_draft(model_input)
        except (KeyError, TypeError, ValueError) as error:
            self.server.record_request(room_id)
            self.send_json(400, {"error": str(error)})
            return
        ordinal = self.server.record_request(room_id)
        time.sleep(self.server.delay_seconds)
        output_text = {"type": "output_text", "text": json.dumps(draft, ensure_ascii=False)}
        self.send_json(200, {
            "id": f"resp_mock_{ordinal}",
            "object": "response",
            "status": "completed",
            "output": [{"type": "message", "role": "assistant", "content": [output_text]}],
        })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18641)
    parser.add_argument("--delay-ms", type=int, default=1000)
    args = parser.parse_args()
    if args.delay_ms < 0:
        parser.error("--delay-ms must be non-negative")
    with ReportMock(args.port, args.delay_ms) as server:
        print(f"Report mock listening at http://127.0.0.1:{server.server_port}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
