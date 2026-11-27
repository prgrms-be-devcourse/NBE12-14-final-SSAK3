"""Expose saved report-load aggregates to Prometheus without running a workload."""

import argparse
import json
import math
from datetime import date
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


METRICS = {
    "end_p95_seconds": "95th percentile time to receive a room-end response.",
    "completion_p95_seconds": "95th percentile time to observe a completed report.",
    "completion_max_seconds": "Maximum time to observe a completed report.",
    "analysis_p95_seconds": "95th percentile DB time from room end to final analysis.",
    "generation_p95_seconds": "95th percentile DB time from final analysis to generated report.",
    "rooms": "Number of rooms configured for the measured run.",
    "verified_rooms": "Number of rooms passing database and provider verification.",
    "completed_rooms": "Number of rooms where the k6 client observed completion.",
}


def read_json(path):
    try:
        with path.open("rb") as handle:
            value = json.load(handle)
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError, RecursionError):
        # The runner writes each result separately. Other valid files remain usable.
        return {}


def field(document, *keys):
    for key in keys:
        if not isinstance(document, dict):
            return None
        document = document.get(key)
    return document


def count(value):
    return value if type(value) is int and value >= 0 else None


def seconds(value, scale=1):
    if type(value) in (float, int) and 0 <= value < math.inf:
        try:
            return value / scale
        except OverflowError:
            pass
    return None


def baseline_results(path):
    data = read_json(path)
    try:
        day = date.fromisoformat(data["date"]).strftime("%Y%m%d")
    except (ValueError, TypeError, KeyError):
        return {}
    rows = data.get("measurements")
    if not isinstance(rows, list):
        return {}
    result = {}
    for row in rows:
        name = field(row, "run")
        rooms = count(field(row, "rooms"))
        if not isinstance(name, str) or not name or not rooms:
            continue
        result[(f"{day}-{name}", rooms, "baseline")] = {
            "end_p95_seconds": seconds(row.get("endHttpP95Ms"), 1000),
            "completion_p95_seconds": seconds(row.get("completedObservedP95Ms"), 1000),
            "completion_max_seconds": seconds(row.get("completedObservedMaxMs"), 1000),
            "analysis_p95_seconds": seconds(row.get("dbEndToFinalizationP95s")),
            "generation_p95_seconds": seconds(row.get("dbFinalizationToReportP95s")),
            "rooms": rooms, "verified_rooms": count(row.get("roomsWithVerifiedReport")),
        }
    return result


def measured_result(run, metadata_path, summary_path, db_path):
    summary = read_json(summary_path).get("metrics")
    db = read_json(db_path)
    if not isinstance(summary, dict) and not db:
        return {}
    rooms = count(field(read_json(metadata_path), "rooms")) or count(db.get("rooms"))
    if not rooms:
        # Older runs stored the fixture size in k6's room-count threshold.
        thresholds = field(summary, "report_rooms_measured", "thresholds")
        if isinstance(thresholds, dict):
            rooms = next((int(k[7:]) for k in thresholds
                          if k.startswith("count==") and k[7:].isascii()
                          and k[7:].isdigit() and len(k[7:]) <= 10), None)
    completed = count(field(summary, "report_completed_rate", "passes"))
    measured = count(field(summary, "report_rooms_measured", "count"))
    values = {"rooms": rooms, "verified_rooms": count(db.get("verified")), "completed_rooms": completed}
    if measured:
        values["end_p95_seconds"] = seconds(field(summary, "room_end_ms", "p(95)"), 1000)
    if completed:
        values["completion_p95_seconds"] = seconds(field(summary, "report_completed_observed_ms", "p(95)"), 1000)
        values["completion_max_seconds"] = seconds(field(summary, "report_completed_observed_ms", "max"), 1000)
    for metric, phase in (("analysis_p95_seconds", "endToAnalysisFinalized"),
                          ("generation_p95_seconds", "analysisFinalizedToReportGenerated")):
        if count(field(db, "dbPhaseSeconds", phase, "samples")):
            values[metric] = seconds(field(db, "dbPhaseSeconds", phase, "p95"))
    return {(run, rooms or "unknown", "local"): values}


def stamp(path):
    try:
        info = path.stat()
        return info.st_mtime_ns, info.st_size
    except OSError:
        return None


def label_value(value):
    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')


class SavedResults:
    def __init__(self, baseline, reports):
        self.baseline = baseline
        self.reports = reports
        self.cache = {}

    def read(self):
        paths = [(self.baseline,)]
        try:
            for directory in sorted(self.reports.iterdir()):
                if directory.is_dir() and "warmup" not in directory.name.lower():
                    paths.append(tuple(directory / name for name in (
                        "run.json", "k6-summary.json", "db-verification.json")))
        except OSError:
            pass
        current = {}
        rows = {}
        for files in paths:
            signature = tuple(stamp(path) for path in files)
            previous = self.cache.get(files)
            if previous and previous[0] == signature:
                values = previous[1]
            elif len(files) == 1:
                values = baseline_results(files[0])
            else:
                values = measured_result(files[0].parent.name, *files)
            current[files] = (signature, values)
            rows.update(values)
        self.cache = current
        return rows

    def render(self):
        rows = self.read()
        lines = []
        for metric, description in METRICS.items():
            name = f"react_report_load_{metric}"
            lines.extend([f"# HELP {name} {description}", f"# TYPE {name} gauge"])
            for (run_id, rooms, source), values in rows.items():
                value = values.get(metric)
                if value is not None:
                    labels = f'run_id="{label_value(run_id)}",rooms="{rooms}",source="{source}"'
                    lines.append(f"{name}{{{labels}}} {value}")
        return ("\n".join(lines) + "\n").encode("utf-8")


class MetricsHandler(BaseHTTPRequestHandler):
    def log_request(self, code="-", size="-"):
        if code != 200:
            super().log_request(code, size)

    def do_GET(self):
        if self.path != "/metrics":
            self.send_error(404)
            return
        content = self.server.saved_results.render()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9108)
    parser.add_argument("--baseline", type=Path, default=Path("/baseline.json"))
    parser.add_argument("--reports", type=Path, default=Path("/reports"))
    args = parser.parse_args()
    with HTTPServer((args.bind, args.port), MetricsHandler) as server:
        server.saved_results = SavedResults(args.baseline, args.reports)
        server.serve_forever()
