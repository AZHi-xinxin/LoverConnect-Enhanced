#!/usr/bin/env python3
"""LoverConnect ingress with coordinate-free safety-location events.

The module intentionally has no framework dependency.  It keeps the existing
``/loverconnect/alert`` contract while adding the durable v1 location event
contract and a small background scheduler.

Privacy boundary:
* raw coordinates are rejected at the HTTP boundary;
* selected RikkaHub user text is processed in memory only;
* SQLite stores only decision metadata and evidence message ids.
"""

from __future__ import annotations

import hmac
import json
import logging
import os
import re
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Iterable, Optional, Protocol
from urllib import error as urllib_error
from urllib import request as urllib_request


LOCATION_EVENT_TYPES = {
    "zone_exit_confirmed",
    "zone_enter_confirmed",
    "distance_tier_crossed",
    "location_degraded",
    "tracking_paused",
    "offline_trip_summary",
    "report_acknowledged",
}
DISTANCE_BUCKETS = {"under_1km", "1_to_5km", "5_to_10km", "over_10km"}
LOCATION_ALLOWED_FIELDS = {
    "event_id",
    "type",
    "away_session_id",
    "zone_id",
    "zone_label",
    "occurred_at",
    "distance_bucket",
    "reported_override",
    "app_version",
}
FORBIDDEN_LOCATION_KEYS = {
    "lat",
    "lng",
    "latitude",
    "longitude",
    "coordinates",
    "coordinate",
    "location",
    "position",
}
ZONE_ID_RE = re.compile(r"^[a-z0-9_-]{1,32}$")
LEGACY_PACKAGE_RE = re.compile(r"^[A-Za-z0-9_.]{1,180}$")
LEGACY_TYPES = {"app_timeout", "night_usage", "manual_test"}

JOB_INITIAL_CHECK = "initial_report_check"
JOB_SECOND_CHECK = "second_report_check"
JOB_ARRIVAL_NOTICE = "arrival_notice"
JOB_OFFLINE_SUMMARY = "offline_summary_notice"
JOB_CORRECTION_NOTICE = "correction_notice"
NOTICE_JOBS = {JOB_ARRIVAL_NOTICE, JOB_OFFLINE_SUMMARY, JOB_CORRECTION_NOTICE}

# The scheduler wakes every 15 seconds. A 45-second grace keeps the initial
# departure notice within one minute of the phone's confirmed third sample,
# while preserving a brief window for the explicit "already reported" button.
INITIAL_NOTICE_DELAY_MS = 45_000


class ValidationError(ValueError):
    pass


class Clock(Protocol):
    def __call__(self) -> int:
        """Return Unix milliseconds."""


class Notifier(Protocol):
    def send_text(self, text: str) -> bool:
        pass


class UserMessageReader(Protocol):
    def selected_user_messages(
        self,
        start_ms: int,
        end_ms: int,
        max_messages: int = 20,
        char_cap: int = 6000,
    ) -> list["UserTextMessage"]:
        pass


class FallbackClassifier(Protocol):
    def classify(self, messages: list["UserTextMessage"]) -> dict[str, Any]:
        pass


def unix_ms() -> int:
    return int(time.time() * 1000)


def _parse_epoch_ms(value: Any) -> int:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValidationError("occurred_at must be a Unix timestamp")
    number = float(value)
    if not number > 0:
        raise ValidationError("occurred_at must be positive")
    return int(number * 1000 if number < 10_000_000_000 else number)


def _is_uuid(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    try:
        uuid.UUID(value)
        return True
    except (ValueError, AttributeError):
        return False


def _contains_forbidden_location_key(value: Any) -> bool:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = re.sub(r"[^a-z]", "", str(key).lower())
            if normalized in FORBIDDEN_LOCATION_KEYS:
                return True
            if _contains_forbidden_location_key(child):
                return True
    elif isinstance(value, list):
        return any(_contains_forbidden_location_key(item) for item in value)
    return False


@dataclass(frozen=True)
class LocationEvent:
    event_id: str
    event_type: str
    away_session_id: str
    zone_id: str
    zone_label: str
    occurred_at_ms: int
    distance_bucket: Optional[str]
    reported_override: bool


@dataclass(frozen=True)
class UserTextMessage:
    message_id: str
    text: str
    created_at_ms: int


@dataclass(frozen=True)
class ReportDecision:
    reported: bool
    confidence: float
    evidence_message_id: Optional[str]
    source: str
    check_succeeded: bool = True


def validate_location_event(body: Any, now_ms: Optional[int] = None) -> LocationEvent:
    now_ms = unix_ms() if now_ms is None else now_ms
    if not isinstance(body, dict):
        raise ValidationError("body must be a JSON object")
    if _contains_forbidden_location_key(body):
        raise ValidationError("raw location coordinates are forbidden")
    unknown = set(body) - LOCATION_ALLOWED_FIELDS
    if unknown:
        raise ValidationError("unsupported field(s): " + ", ".join(sorted(unknown)))

    event_id = body.get("event_id")
    session_id = body.get("away_session_id")
    event_type = body.get("type")
    zone_id = body.get("zone_id")
    zone_label = body.get("zone_label")
    occurred_at_ms = _parse_epoch_ms(body.get("occurred_at"))
    distance_bucket = body.get("distance_bucket")
    reported_override = body.get("reported_override", False)

    if not _is_uuid(event_id):
        raise ValidationError("event_id must be a UUID")
    if not _is_uuid(session_id):
        raise ValidationError("away_session_id must be a UUID")
    if event_type not in LOCATION_EVENT_TYPES:
        raise ValidationError("unsupported event type")
    if not isinstance(zone_id, str) or not ZONE_ID_RE.fullmatch(zone_id):
        raise ValidationError("invalid zone_id")
    if not isinstance(zone_label, str) or not zone_label.strip() or len(zone_label.strip()) > 24:
        raise ValidationError("invalid zone_label")
    if occurred_at_ms > now_ms + 10 * 60_000:
        raise ValidationError("future occurred_at")
    if occurred_at_ms < now_ms - 30 * 24 * 60 * 60_000:
        raise ValidationError("occurred_at is too old")
    if distance_bucket is not None and distance_bucket not in DISTANCE_BUCKETS:
        raise ValidationError("invalid distance_bucket")
    if event_type == "distance_tier_crossed" and distance_bucket is None:
        raise ValidationError("distance_tier_crossed requires distance_bucket")
    if not isinstance(reported_override, bool):
        raise ValidationError("reported_override must be boolean")
    app_version = body.get("app_version")
    if app_version is not None and (not isinstance(app_version, str) or len(app_version) > 32):
        raise ValidationError("invalid app_version")

    return LocationEvent(
        event_id=event_id,
        event_type=event_type,
        away_session_id=session_id,
        zone_id=zone_id,
        zone_label=zone_label.strip(),
        occurred_at_ms=occurred_at_ms,
        distance_bucket=distance_bucket,
        reported_override=reported_override,
    )


class _ClosingConnection(sqlite3.Connection):
    """Commit/rollback like sqlite3.Connection, then release the file handle."""

    def __exit__(self, exc_type: Any, exc_value: Any, traceback: Any) -> bool:
        try:
            return bool(super().__exit__(exc_type, exc_value, traceback))
        finally:
            self.close()


class SQLiteStore:
    """Durable metadata store.  No conversation body or coordinates exist here."""

    def __init__(self, path: str | Path):
        self.path = str(path)
        self._lock = threading.RLock()
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=10, factory=_ClosingConnection)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA busy_timeout=5000")
        return conn

    def _initialize(self) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS location_events (
                    event_id TEXT PRIMARY KEY,
                    event_type TEXT NOT NULL,
                    away_session_id TEXT NOT NULL,
                    zone_id TEXT NOT NULL,
                    zone_label TEXT NOT NULL,
                    occurred_at_ms INTEGER NOT NULL,
                    distance_bucket TEXT,
                    reported_override INTEGER NOT NULL DEFAULT 0,
                    received_at_ms INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'accepted'
                );
                CREATE INDEX IF NOT EXISTS idx_location_events_session
                    ON location_events(away_session_id, occurred_at_ms);

                CREATE TABLE IF NOT EXISTS away_sessions (
                    away_session_id TEXT PRIMARY KEY,
                    origin_zone_id TEXT,
                    origin_zone_label TEXT,
                    departed_at_ms INTEGER,
                    state TEXT NOT NULL DEFAULT 'away',
                    reported INTEGER NOT NULL DEFAULT 0,
                    evidence_message_id TEXT,
                    report_source TEXT,
                    reported_at_ms INTEGER,
                    first_alert_sent_at_ms INTEGER,
                    second_alert_sent_at_ms INTEGER,
                    arrived_zone_id TEXT,
                    arrived_zone_label TEXT,
                    arrived_at_ms INTEGER,
                    arrival_notice_sent_at_ms INTEGER,
                    offline_summary_sent_at_ms INTEGER,
                    correction_sent_at_ms INTEGER,
                    updated_at_ms INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    away_session_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    due_at_ms INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending',
                    lease_until_ms INTEGER,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error_type TEXT,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL,
                    UNIQUE(away_session_id, kind)
                );
                CREATE INDEX IF NOT EXISTS idx_jobs_due ON jobs(status, due_at_ms);

                CREATE TABLE IF NOT EXISTS deliveries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    away_session_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    delivered_at_ms INTEGER NOT NULL,
                    UNIQUE(away_session_id, kind)
                );
                CREATE INDEX IF NOT EXISTS idx_deliveries_time ON deliveries(delivered_at_ms);
                """
            )
            job_columns = {row["name"] for row in conn.execute("PRAGMA table_info(jobs)")}
            if "lease_until_ms" not in job_columns:
                conn.execute("ALTER TABLE jobs ADD COLUMN lease_until_ms INTEGER")

    def insert_event(self, event: LocationEvent, received_at_ms: int) -> bool:
        with self._lock, self._connect() as conn:
            try:
                conn.execute(
                    """
                    INSERT INTO location_events(
                        event_id,event_type,away_session_id,zone_id,zone_label,
                        occurred_at_ms,distance_bucket,reported_override,received_at_ms
                    ) VALUES(?,?,?,?,?,?,?,?,?)
                    """,
                    (
                        event.event_id,
                        event.event_type,
                        event.away_session_id,
                        event.zone_id,
                        event.zone_label,
                        event.occurred_at_ms,
                        event.distance_bucket,
                        int(event.reported_override),
                        received_at_ms,
                    ),
                )
                return True
            except sqlite3.IntegrityError:
                return False

    def set_event_status(self, event_id: str, status: str) -> None:
        with self._lock, self._connect() as conn:
            conn.execute("UPDATE location_events SET status=? WHERE event_id=?", (status, event_id))

    def ensure_departure(self, event: LocationEvent, now_ms: int) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                INSERT INTO away_sessions(
                    away_session_id,origin_zone_id,origin_zone_label,departed_at_ms,state,updated_at_ms
                ) VALUES(?,?,?,?, 'away', ?)
                ON CONFLICT(away_session_id) DO UPDATE SET
                    origin_zone_id=COALESCE(away_sessions.origin_zone_id, excluded.origin_zone_id),
                    origin_zone_label=COALESCE(away_sessions.origin_zone_label, excluded.origin_zone_label),
                    departed_at_ms=COALESCE(away_sessions.departed_at_ms, excluded.departed_at_ms),
                    updated_at_ms=excluded.updated_at_ms
                """,
                (
                    event.away_session_id,
                    event.zone_id,
                    event.zone_label,
                    event.occurred_at_ms,
                    now_ms,
                ),
            )

    def ensure_session(self, event: LocationEvent, now_ms: int) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                INSERT OR IGNORE INTO away_sessions(
                    away_session_id,origin_zone_id,origin_zone_label,departed_at_ms,state,updated_at_ms
                ) VALUES(?,?,?,?, 'away', ?)
                """,
                (
                    event.away_session_id,
                    event.zone_id,
                    event.zone_label,
                    event.occurred_at_ms,
                    now_ms,
                ),
            )

    def mark_arrived(self, event: LocationEvent, now_ms: int) -> None:
        self.ensure_session(event, now_ms)
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                UPDATE away_sessions SET
                    state='arrived', arrived_zone_id=?, arrived_zone_label=?,
                    arrived_at_ms=?, updated_at_ms=?
                WHERE away_session_id=?
                """,
                (
                    event.zone_id,
                    event.zone_label,
                    event.occurred_at_ms,
                    now_ms,
                    event.away_session_id,
                ),
            )

    def mark_reported(
        self,
        session_id: str,
        now_ms: int,
        source: str,
        evidence_message_id: Optional[str] = None,
    ) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                UPDATE away_sessions SET reported=1, evidence_message_id=?, report_source=?,
                    reported_at_ms=?, updated_at_ms=? WHERE away_session_id=?
                """,
                (evidence_message_id, source, now_ms, now_ms, session_id),
            )

    def session(self, session_id: str) -> Optional[dict[str, Any]]:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM away_sessions WHERE away_session_id=?", (session_id,)
            ).fetchone()
            return dict(row) if row else None

    def schedule_job(self, session_id: str, kind: str, due_at_ms: int, now_ms: int) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                INSERT OR IGNORE INTO jobs(
                    away_session_id,kind,due_at_ms,status,created_at_ms,updated_at_ms
                ) VALUES(?,?,?,'pending',?,?)
                """,
                (session_id, kind, due_at_ms, now_ms, now_ms),
            )

    def cancel_report_jobs(self, session_id: str, now_ms: int) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                UPDATE jobs SET status='cancelled',lease_until_ms=NULL,updated_at_ms=?
                WHERE away_session_id=? AND kind IN (?,?) AND status IN ('pending','running')
                """,
                (now_ms, session_id, JOB_INITIAL_CHECK, JOB_SECOND_CHECK),
            )

    def claim_due_jobs(
        self,
        now_ms: int,
        limit: int = 20,
        lease_ms: int = 2 * 60_000,
    ) -> list[dict[str, Any]]:
        """Atomically lease due work so concurrent schedulers cannot double-send."""

        with self._lock, self._connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            rows = conn.execute(
                """
                SELECT * FROM jobs
                WHERE due_at_ms<=?
                  AND (status='pending' OR (status='running' AND lease_until_ms<=?))
                ORDER BY due_at_ms,id LIMIT ?
                """,
                (now_ms, now_ms, max(1, min(limit, 100))),
            ).fetchall()
            ids = [int(row["id"]) for row in rows]
            if not ids:
                return []
            placeholders = ",".join("?" for _ in ids)
            conn.execute(
                f"UPDATE jobs SET status='running',lease_until_ms=?,updated_at_ms=? "
                f"WHERE id IN ({placeholders})",
                (now_ms + lease_ms, now_ms, *ids),
            )
            claimed = conn.execute(
                f"SELECT * FROM jobs WHERE id IN ({placeholders}) ORDER BY due_at_ms,id",
                ids,
            ).fetchall()
            return [dict(row) for row in claimed]

    def finish_job(self, job_id: int, status: str, now_ms: int) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                "UPDATE jobs SET status=?,lease_until_ms=NULL,updated_at_ms=? WHERE id=?",
                (status, now_ms, job_id),
            )

    def retry_job(self, job_id: int, due_at_ms: int, error_type: str, now_ms: int) -> None:
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                UPDATE jobs SET status='pending',lease_until_ms=NULL,due_at_ms=?,
                    attempts=attempts+1,last_error_type=?,updated_at_ms=?
                WHERE id=? AND status='running'
                """,
                (due_at_ms, error_type[:64], now_ms, job_id),
            )

    def has_distance_event(self, session_id: str) -> bool:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                """
                SELECT 1 FROM location_events
                WHERE away_session_id=? AND event_type='distance_tier_crossed' LIMIT 1
                """,
                (session_id,),
            ).fetchone()
            return row is not None

    def distance_event_time(self, session_id: str) -> Optional[int]:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                """
                SELECT MIN(occurred_at_ms) AS value FROM location_events
                WHERE away_session_id=? AND event_type='distance_tier_crossed'
                """,
                (session_id,),
            ).fetchone()
            return int(row["value"]) if row and row["value"] is not None else None

    def record_delivery(self, session_id: str, kind: str, now_ms: int) -> bool:
        with self._lock, self._connect() as conn:
            try:
                conn.execute(
                    "INSERT INTO deliveries(away_session_id,kind,delivered_at_ms) VALUES(?,?,?)",
                    (session_id, kind, now_ms),
                )
                column = {
                    JOB_INITIAL_CHECK: "first_alert_sent_at_ms",
                    JOB_SECOND_CHECK: "second_alert_sent_at_ms",
                    JOB_ARRIVAL_NOTICE: "arrival_notice_sent_at_ms",
                    JOB_OFFLINE_SUMMARY: "offline_summary_sent_at_ms",
                    JOB_CORRECTION_NOTICE: "correction_sent_at_ms",
                }[kind]
                conn.execute(
                    f"UPDATE away_sessions SET {column}=?,updated_at_ms=? WHERE away_session_id=?",
                    (now_ms, now_ms, session_id),
                )
                return True
            except sqlite3.IntegrityError:
                return False

    def delivery_slot(self, now_ms: int, hourly_limit: int = 4) -> tuple[bool, int]:
        cutoff = now_ms - 3_600_000
        with self._lock, self._connect() as conn:
            rows = conn.execute(
                "SELECT delivered_at_ms FROM deliveries WHERE delivered_at_ms>? ORDER BY delivered_at_ms",
                (cutoff,),
            ).fetchall()
        if len(rows) < hourly_limit:
            return True, now_ms
        return False, int(rows[0]["delivered_at_ms"]) + 3_600_001

    def table_columns(self) -> dict[str, list[str]]:
        """Used by privacy regression tests and diagnostics."""
        result: dict[str, list[str]] = {}
        with self._lock, self._connect() as conn:
            for table in ("location_events", "away_sessions", "jobs", "deliveries"):
                result[table] = [row["name"] for row in conn.execute(f"PRAGMA table_info({table})")]
        return result


class RikkaClient(UserMessageReader, Notifier):
    """Minimal adapter for the official RikkaHub Web API."""

    def __init__(
        self,
        base_url: str,
        conversation_id: str,
        read_token: str = "",
        send_with_auth: bool = False,
        timeout_seconds: float = 15.0,
    ):
        self.base_url = base_url.rstrip("/")
        self.conversation_id = conversation_id
        self.read_token = read_token
        self.send_with_auth = send_with_auth
        self.timeout_seconds = timeout_seconds

    def _headers(self, include_auth: bool) -> dict[str, str]:
        headers = {"Accept": "application/json", "Content-Type": "application/json; charset=utf-8"}
        if include_auth and self.read_token:
            headers["Authorization"] = "Bearer " + self.read_token
        return headers

    def _json_request(
        self,
        method: str,
        path: str,
        payload: Optional[dict[str, Any]] = None,
        include_auth: bool = True,
    ) -> tuple[int, Any]:
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib_request.Request(
            self.base_url + path,
            data=body,
            method=method,
            headers=self._headers(include_auth),
        )
        try:
            with urllib_request.urlopen(req, timeout=self.timeout_seconds) as response:
                raw = response.read()
                return response.status, json.loads(raw.decode("utf-8")) if raw else {}
        except urllib_error.HTTPError as exc:
            try:
                exc.read()
                return exc.code, {}
            finally:
                exc.close()

    def send_text(self, text: str) -> bool:
        status, _ = self._json_request(
            "POST",
            f"/api/conversations/{self.conversation_id}/messages",
            {"parts": [{"text": text, "type": "text"}]},
            include_auth=self.send_with_auth,
        )
        return 200 <= status < 300

    def selected_user_messages(
        self,
        start_ms: int,
        end_ms: int,
        max_messages: int = 20,
        char_cap: int = 6000,
    ) -> list[UserTextMessage]:
        status, data = self._json_request(
            "GET",
            f"/api/conversations/{self.conversation_id}",
            include_auth=True,
        )
        if status != 200 or not isinstance(data, dict):
            raise RuntimeError(f"RikkaHub conversation read failed ({status})")
        return self.parse_selected_user_messages(data, start_ms, end_ms, max_messages, char_cap)

    @staticmethod
    def parse_selected_user_messages(
        data: dict[str, Any],
        start_ms: int,
        end_ms: int,
        max_messages: int = 20,
        char_cap: int = 6000,
    ) -> list[UserTextMessage]:
        nodes = data.get("messages")
        if not isinstance(nodes, list):
            nodes = data.get("messageNodes")
        if not isinstance(nodes, list):
            return []
        selected: list[UserTextMessage] = []
        for node in nodes:
            if not isinstance(node, dict) or not isinstance(node.get("messages"), list):
                continue
            branches = node["messages"]
            index = node.get("selectIndex", 0)
            if not isinstance(index, int) or index < 0 or index >= len(branches):
                index = 0
            if not branches:
                continue
            message = branches[index]
            if not isinstance(message, dict) or str(message.get("role", "")).lower() != "user":
                continue
            created_at_ms = _parse_rikka_time(message.get("createdAt"))
            if created_at_ms is None or created_at_ms < start_ms or created_at_ms > end_ms:
                continue
            texts = []
            for part in message.get("parts", []):
                if isinstance(part, dict) and part.get("type") == "text" and isinstance(part.get("text"), str):
                    texts.append(part["text"])
            text = "\n".join(texts).strip()
            message_id = message.get("id")
            if text and isinstance(message_id, str) and message_id:
                selected.append(UserTextMessage(message_id, text, created_at_ms))
        selected.sort(key=lambda item: item.created_at_ms)
        selected = selected[-max(1, min(max_messages, 100)) :]
        kept: list[UserTextMessage] = []
        used = 0
        for item in reversed(selected):
            remaining = char_cap - used
            if remaining <= 0:
                break
            clipped = item.text[-remaining:]
            kept.append(UserTextMessage(item.message_id, clipped, item.created_at_ms))
            used += len(clipped)
        return list(reversed(kept))


def _parse_rikka_time(value: Any) -> Optional[int]:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return _parse_epoch_ms(value)
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        normalized = value.strip().replace("Z", "+00:00")
        parsed = datetime.fromisoformat(normalized)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp() * 1000)
    except ValueError:
        return None


class OpenAICompatibleClassifier(FallbackClassifier):
    def __init__(self, endpoint: str, api_key: str, model: str, timeout_seconds: float = 20.0):
        endpoint = endpoint.rstrip("/")
        self.endpoint = endpoint if endpoint.endswith("/chat/completions") else endpoint + "/chat/completions"
        self.api_key = api_key
        self.model = model
        self.timeout_seconds = timeout_seconds

    def classify(self, messages: list[UserTextMessage]) -> dict[str, Any]:
        compact = [{"id": item.message_id, "text": item.text} for item in messages]
        instruction = (
            "判断用户是否已经明确向当前AI报备本次外出或返程。"
            "第一人称明确计划（如我要出门、一会儿回家）也算报备。"
            "否定、取消、疑问、假设或转述他人的句子判false。"
            "只输出JSON：{\"reported\":bool,\"confidence\":0..1,"
            "\"evidence_message_id\":string|null}。证据ID必须来自输入。\n"
            + json.dumps(compact, ensure_ascii=False)
        )
        body = json.dumps(
            {
                "model": self.model,
                "messages": [{"role": "user", "content": instruction}],
                "temperature": 0,
                "response_format": {"type": "json_object"},
            },
            ensure_ascii=False,
        ).encode("utf-8")
        req = urllib_request.Request(
            self.endpoint,
            data=body,
            method="POST",
            headers={
                "Authorization": "Bearer " + self.api_key,
                "Content-Type": "application/json; charset=utf-8",
            },
        )
        with urllib_request.urlopen(req, timeout=self.timeout_seconds) as response:
            data = json.loads(response.read().decode("utf-8"))
        content = data["choices"][0]["message"]["content"]
        if not isinstance(content, str):
            raise RuntimeError("classifier returned non-text content")
        match = re.search(r"\{.*\}", content, re.S)
        if not match:
            raise RuntimeError("classifier returned invalid JSON")
        return json.loads(match.group(0))


class RuleFirstReportChecker:
    DISQUALIFYING_PATTERNS = tuple(
        re.compile(pattern)
        for pattern in (
            r"我.*(不出门了|不出去了|不去了|不走了)",
            r"我.*取消.*(出门|外出|行程|返程)",
            r"如果我.*(出门|外出|回家|返程)",
            r"假如我.*(出门|外出|回家|返程)",
            r"不是我.*出门",
            r"我.*(出门|外出|回家|返程).*(吗|么|没|没有|\?|？)$",
        )
    )
    POSITIVE_PATTERNS = tuple(
        re.compile(pattern)
        for pattern in (
            r"我(已经|刚刚|刚|现在|已)?(出门|出发|离开家|离开工作地点|离开公司|离开宿舍)了?",
            r"我(已经|刚刚|刚|现在|已)?(到家|到工作地点|到宿舍|到公司|到了)了?",
            r"我(已经|现在)?在外面",
            r"我(今天|现在|一会儿|等会儿|待会儿|马上|稍后)?"
            r"(要|准备|打算|计划|会)(出门|出发|离开|去工作地点|去公司|回家|回工作地点|回公司|回宿舍)",
            r"我(一会儿|等会儿|待会儿|马上|稍后)"
            r"(出门|出发|去工作地点|去公司|回家|回工作地点|回公司|回宿舍)",
            r"我已(经)?向.*(报备|说明|告诉)",
            r"我已(经)?(告诉|告知)(你|对方|联系人)",
        )
    )

    def __init__(
        self,
        reader: UserMessageReader,
        fallback: Optional[FallbackClassifier] = None,
        confidence_threshold: float = 0.85,
    ):
        self.reader = reader
        self.fallback = fallback
        self.confidence_threshold = confidence_threshold

    def check(self, start_ms: int, end_ms: int) -> ReportDecision:
        try:
            messages = self.reader.selected_user_messages(start_ms, end_ms, 20, 6000)
        except Exception:
            return ReportDecision(False, 0.0, None, "reader_unavailable", check_succeeded=False)

        for message in reversed(messages):
            normalized = re.sub(r"\s+", "", message.text)
            if any(pattern.search(normalized) for pattern in self.DISQUALIFYING_PATTERNS):
                continue
            if any(pattern.search(normalized) for pattern in self.POSITIVE_PATTERNS):
                return ReportDecision(True, 1.0, message.message_id, "rule")

        if not messages or self.fallback is None:
            return ReportDecision(False, 0.0, None, "rule")
        try:
            raw = self.fallback.classify(messages)
            reported = raw.get("reported") is True
            confidence = float(raw.get("confidence", 0.0))
            evidence = raw.get("evidence_message_id")
            valid_ids = {item.message_id for item in messages}
            if (
                reported
                and confidence >= self.confidence_threshold
                and isinstance(evidence, str)
                and evidence in valid_ids
            ):
                return ReportDecision(True, confidence, evidence, "model")
            return ReportDecision(False, confidence, None, "model")
        except Exception:
            return ReportDecision(False, 0.0, None, "classifier_unavailable", check_succeeded=False)


class LocationEventService:
    def __init__(
        self,
        store: SQLiteStore,
        checker: RuleFirstReportChecker,
        notifier: Notifier,
        clock: Clock = unix_ms,
        initial_grace_ms: int = INITIAL_NOTICE_DELAY_MS,
        second_delay_ms: int = 15 * 60_000,
        hourly_delivery_limit: int = 4,
    ):
        self.store = store
        self.checker = checker
        self.notifier = notifier
        self.clock = clock
        self.initial_grace_ms = initial_grace_ms
        self.second_delay_ms = second_delay_ms
        self.hourly_delivery_limit = hourly_delivery_limit
        self._run_lock = threading.RLock()

    def accept(self, body: Any) -> tuple[int, dict[str, Any]]:
        with self._run_lock:
            return self._accept(body)

    def _accept(self, body: Any) -> tuple[int, dict[str, Any]]:
        now_ms = self.clock()
        try:
            event = validate_location_event(body, now_ms)
        except ValidationError as exc:
            return 400, {"error": str(exc)}
        if not self.store.insert_event(event, now_ms):
            return 200, {"status": "duplicate", "event_id": event.event_id}

        if event.event_type == "zone_exit_confirmed":
            self.store.ensure_departure(event, now_ms)
            if event.reported_override:
                self.store.mark_reported(event.away_session_id, now_ms, "phone_one_shot")
                self.store.set_event_status(event.event_id, "suppressed_reported")
            else:
                due = max(now_ms, event.occurred_at_ms + self.initial_grace_ms)
                self.store.schedule_job(event.away_session_id, JOB_INITIAL_CHECK, due, now_ms)
        elif event.event_type == "zone_enter_confirmed":
            self.store.mark_arrived(event, now_ms)
            self.store.cancel_report_jobs(event.away_session_id, now_ms)
            self.store.schedule_job(event.away_session_id, JOB_ARRIVAL_NOTICE, now_ms, now_ms)
        elif event.event_type == "distance_tier_crossed":
            self.store.ensure_session(event, now_ms)
            session = self.store.session(event.away_session_id) or {}
            first = session.get("first_alert_sent_at_ms")
            if first and not session.get("reported") and session.get("state") == "away":
                due = max(now_ms, event.occurred_at_ms, int(first) + self.second_delay_ms)
                self.store.schedule_job(event.away_session_id, JOB_SECOND_CHECK, due, now_ms)
        elif event.event_type == "offline_trip_summary":
            self.store.mark_arrived(event, now_ms)
            if event.reported_override:
                self.store.mark_reported(event.away_session_id, now_ms, "phone_one_shot")
            self.store.cancel_report_jobs(event.away_session_id, now_ms)
            self.store.schedule_job(event.away_session_id, JOB_OFFLINE_SUMMARY, now_ms, now_ms)
        elif event.event_type == "report_acknowledged":
            self.store.ensure_session(event, now_ms)
            before = self.store.session(event.away_session_id) or {}
            self.store.mark_reported(event.away_session_id, now_ms, "phone_acknowledgement")
            self.store.cancel_report_jobs(event.away_session_id, now_ms)
            if before.get("first_alert_sent_at_ms") or before.get("second_alert_sent_at_ms"):
                self.store.schedule_job(event.away_session_id, JOB_CORRECTION_NOTICE, now_ms, now_ms)
        else:
            self.store.ensure_session(event, now_ms)
            self.store.set_event_status(event.event_id, "diagnostic_only")

        self.run_due_jobs_once(limit=10)
        return 202, {"status": "accepted", "event_id": event.event_id}

    def run_due_jobs_once(self, limit: int = 20) -> int:
        with self._run_lock:
            return self._run_due_jobs_once(limit)

    def _run_due_jobs_once(self, limit: int = 20) -> int:
        now_ms = self.clock()
        processed = 0
        for job in self.store.claim_due_jobs(now_ms, limit):
            processed += 1
            self._run_job(job, now_ms)
        return processed

    def _run_job(self, job: dict[str, Any], now_ms: int) -> None:
        session_id = job["away_session_id"]
        kind = job["kind"]
        session = self.store.session(session_id)
        if session is None:
            self.store.finish_job(job["id"], "cancelled", now_ms)
            return

        if kind in (JOB_INITIAL_CHECK, JOB_SECOND_CHECK):
            if session.get("state") != "away" or session.get("reported"):
                self.store.finish_job(job["id"], "cancelled", now_ms)
                return
            if kind == JOB_SECOND_CHECK and not session.get("first_alert_sent_at_ms"):
                self.store.retry_job(job["id"], now_ms + 60_000, "first_alert_pending", now_ms)
                return
            departed_at = int(session.get("departed_at_ms") or now_ms)
            end_ms = departed_at + self.initial_grace_ms if kind == JOB_INITIAL_CHECK else now_ms
            decision = self.checker.check(departed_at - 6 * 60 * 60_000, end_ms)
            if decision.reported:
                self.store.mark_reported(
                    session_id,
                    now_ms,
                    decision.source,
                    decision.evidence_message_id,
                )
                self.store.cancel_report_jobs(session_id, now_ms)
                self.store.finish_job(job["id"], "suppressed_reported", now_ms)
                return
            if not decision.check_succeeded and int(job.get("attempts", 0)) < 2:
                retry_ms = now_ms + (int(job.get("attempts", 0)) + 1) * 60_000
                self.store.retry_job(job["id"], retry_ms, decision.source, now_ms)
                return

        allowed, retry_at = self.store.delivery_slot(now_ms, self.hourly_delivery_limit)
        if not allowed:
            self.store.retry_job(job["id"], retry_at, "delivery_rate_limit", now_ms)
            return
        text = self._notice_text(kind, session)
        if text is None:
            self.store.finish_job(job["id"], "cancelled", now_ms)
            return
        try:
            sent = self.notifier.send_text(text)
        except Exception:
            sent = False
        if not sent:
            delay = min(15 * 60_000, 60_000 * (2 ** min(int(job.get("attempts", 0)), 4)))
            self.store.retry_job(job["id"], now_ms + delay, "notifier_unavailable", now_ms)
            return

        self.store.record_delivery(session_id, kind, now_ms)
        self.store.finish_job(job["id"], "completed", now_ms)
        if kind == JOB_INITIAL_CHECK and self.store.has_distance_event(session_id):
            distance_at = self.store.distance_event_time(session_id) or now_ms
            self.store.schedule_job(
                session_id,
                JOB_SECOND_CHECK,
                max(distance_at, now_ms + self.second_delay_ms),
                now_ms,
            )

    @staticmethod
    def _notice_text(kind: str, session: dict[str, Any]) -> Optional[str]:
        origin = session.get("origin_zone_label") or "安全区域"
        arrived = session.get("arrived_zone_label") or "安全区域"
        if kind == JOB_INITIAL_CHECK:
            return f"【安全位置播报】已连续三次定位确认用户离开「{origin}」，当前对话中尚未核对到报备，请确认是否平安。"
        if kind == JOB_SECOND_CHECK:
            return "【安全位置播报】用户仍在外且已越过设定距离，尚未核对到报备；这是本次外出的最后一次距离提醒。"
        if kind == JOB_ARRIVAL_NOTICE:
            return f"【安全位置播报】用户已到达「{arrived}」。"
        if kind == JOB_OFFLINE_SUMMARY:
            return f"【安全位置播报】离线期间完成了一次外出，现已到达「{arrived}」；已合并为一条摘要，不补发过时的离开提醒。"
        if kind == JOB_CORRECTION_NOTICE:
            return "【安全位置播报】已收到本次外出的补充报备，后续提醒已取消。"
        return None


class LegacyAlertService:
    """Compatibility implementation for the existing app-usage endpoint."""

    def __init__(self, state_path: str | Path, notifier: Notifier, clock_seconds: Callable[[], float] = time.time):
        self.state_path = Path(state_path)
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        self.notifier = notifier
        self.clock_seconds = clock_seconds
        self._lock = threading.Lock()

    def _load(self) -> dict[str, Any]:
        try:
            value = json.loads(self.state_path.read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else {}
        except (FileNotFoundError, json.JSONDecodeError, OSError):
            return {}

    def _save(self, value: dict[str, Any]) -> None:
        temp = self.state_path.with_suffix(self.state_path.suffix + ".tmp")
        temp.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        os.replace(temp, self.state_path)

    def accept(self, body: Any) -> tuple[int, dict[str, Any]]:
        event, error = self._validate(body)
        if error:
            return 400, {"error": error}
        accepted, reason = self._reserve(event)
        if not accepted:
            return (429 if reason in {"cooldown", "rate_limited"} else 200), {"status": reason}
        try:
            sent = self.notifier.send_text(self._message(event))
        except Exception:
            sent = False
        if sent:
            return 202, {"status": "forwarded"}
        return 502, {"error": "RikkaHub unavailable"}

    def _validate(self, body: Any) -> tuple[Optional[dict[str, Any]], Optional[str]]:
        if not isinstance(body, dict):
            return None, "body must be a JSON object"
        event_type = body.get("type")
        event_id = body.get("event_id")
        timestamp = body.get("timestamp")
        package = body.get("app_package", "")
        label = body.get("app_label", "")
        duration = body.get("duration_minutes", 0)
        if event_type not in LEGACY_TYPES:
            return None, "unsupported event type"
        if not isinstance(event_id, str) or not (8 <= len(event_id) <= 128):
            return None, "invalid event_id"
        if not isinstance(timestamp, (int, float)) or abs(self.clock_seconds() - timestamp) > 600:
            return None, "stale timestamp"
        if package and (not isinstance(package, str) or not LEGACY_PACKAGE_RE.fullmatch(package)):
            return None, "invalid app_package"
        if not isinstance(label, str) or len(label) > 64:
            return None, "invalid app_label"
        if not isinstance(duration, (int, float)) or not (0 <= duration <= 1440):
            return None, "invalid duration_minutes"
        return {
            "event_id": event_id,
            "type": event_type,
            "timestamp": float(timestamp),
            "app_package": package,
            "app_label": label.strip() or "某个应用",
            "duration_minutes": int(duration),
        }, None

    def _reserve(self, event: dict[str, Any]) -> tuple[bool, str]:
        now = self.clock_seconds()
        key = f"{event['type']}:{event['app_package']}"
        with self._lock:
            state = self._load()
            seen = state.setdefault("seen", {})
            cooldowns = state.setdefault("cooldowns", {})
            hourly = [value for value in state.setdefault("hourly", []) if now - value < 3600]
            state["hourly"] = hourly
            seen = {item: value for item, value in seen.items() if now - value < 86400}
            state["seen"] = seen
            if event["event_id"] in seen:
                return False, "duplicate"
            if event["type"] != "manual_test" and now - cooldowns.get(key, 0) < 1800:
                return False, "cooldown"
            if len(hourly) >= 6:
                return False, "rate_limited"
            seen[event["event_id"]] = now
            cooldowns[key] = now
            hourly.append(now)
            self._save(state)
            return True, "accepted"

    @staticmethod
    def _message(event: dict[str, Any]) -> str:
        if event["type"] == "app_timeout":
            detail = f"用户已经连续使用「{event['app_label']}」约 {event['duration_minutes']} 分钟，请温柔提醒她休息一下。"
        elif event["type"] == "night_usage":
            detail = f"现在是夜间，用户仍在使用「{event['app_label']}」，请看看时间并温柔提醒她休息。"
        else:
            detail = "小L到哨兵的提醒通道测试成功，请确认收到。"
        return f"【小L提醒】\n{detail}\n[{datetime.now():%Y-%m-%d %H:%M:%S}]"


class SlidingWindowLimiter:
    def __init__(self, limit: int = 60, window_seconds: int = 60):
        self.limit = limit
        self.window_seconds = window_seconds
        self._values: dict[str, list[float]] = {}
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            values = [item for item in self._values.get(key, []) if now - item < self.window_seconds]
            if len(values) >= self.limit:
                self._values[key] = values
                return False
            values.append(now)
            self._values[key] = values
            return True


@dataclass(frozen=True)
class AppConfig:
    ingress_token: str
    bind_host: str
    bind_port: int
    database_path: str
    legacy_state_path: str


class IngressApplication:
    def __init__(
        self,
        config: AppConfig,
        location_service: LocationEventService,
        legacy_service: LegacyAlertService,
    ):
        self.config = config
        self.location_service = location_service
        self.legacy_service = legacy_service
        self.request_limiter = SlidingWindowLimiter()

    def authorized(self, header: str) -> bool:
        expected = "Bearer " + self.config.ingress_token
        return hmac.compare_digest(header.encode("utf-8"), expected.encode("utf-8"))


def make_handler(application: IngressApplication) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "LoverConnectIngress/2.0"

        def _reply(self, status: int, body: dict[str, Any]) -> None:
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self) -> None:  # noqa: N802
            if self.path == "/health":
                self._reply(200, {"status": "ok", "location_events": "ready"})
            else:
                self._reply(404, {"error": "not found"})

        def do_POST(self) -> None:  # noqa: N802
            if self.path not in {"/loverconnect/alert", "/loverconnect/v1/location-events"}:
                self._reply(404, {"error": "not found"})
                return
            if not application.request_limiter.allow(self.client_address[0]):
                self._reply(429, {"error": "request rate limit"})
                return
            if not application.authorized(self.headers.get("Authorization", "")):
                self._reply(401, {"error": "unauthorized"})
                return
            content_type = self.headers.get("Content-Type", "").lower()
            if not content_type.startswith("application/json"):
                self._reply(415, {"error": "Content-Type must be application/json"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = 0
            if not (1 <= length <= 16_384):
                self._reply(413, {"error": "invalid body size"})
                return
            try:
                body = json.loads(self.rfile.read(length).decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                self._reply(400, {"error": "invalid JSON"})
                return
            if self.path == "/loverconnect/alert":
                service = application.legacy_service
            else:
                service = application.location_service
            try:
                status, response = service.accept(body)
            except Exception as exc:
                logging.error("request failed path=%s error_type=%s", self.path, type(exc).__name__)
                self._reply(500, {"error": "internal service error"})
                return
            self._reply(status, response)

        def log_message(self, fmt: str, *args: object) -> None:
            return

    return Handler


def _required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"required environment variable is missing: {name}")
    return value


def build_application_from_env() -> IngressApplication:
    base_dir = Path(os.environ.get("LC_BASE_DIR", "/root/sentinel"))
    bind_port = int(os.environ.get("LC_BIND_PORT", "8790"))
    if not 1 <= bind_port <= 65_535:
        raise RuntimeError("LC_BIND_PORT must be between 1 and 65535")
    config = AppConfig(
        ingress_token=_required_env("LC_INGRESS_TOKEN"),
        bind_host=os.environ.get("LC_BIND_HOST", "127.0.0.1"),
        bind_port=bind_port,
        database_path=os.environ.get("LC_LOCATION_DB", str(base_dir / "loverconnect_location.db")),
        legacy_state_path=os.environ.get("LC_LEGACY_STATE", str(base_dir / "loverconnect_state.json")),
    )
    rikka = RikkaClient(
        _required_env("RIKKA_API"),
        _required_env("RIKKA_CONV_ID"),
        read_token=_required_env("RIKKA_API_TOKEN"),
        send_with_auth=os.environ.get("RIKKA_SEND_WITH_AUTH", "false").lower() == "true",
    )
    fallback: Optional[FallbackClassifier] = None
    llm_url = os.environ.get("LC_REPORT_LLM_URL", "").strip()
    llm_key = os.environ.get("LC_REPORT_LLM_KEY", "").strip()
    llm_model = os.environ.get("LC_REPORT_LLM_MODEL", "").strip()
    if llm_url and llm_key and llm_model:
        fallback = OpenAICompatibleClassifier(llm_url, llm_key, llm_model)
    store = SQLiteStore(config.database_path)
    checker = RuleFirstReportChecker(rikka, fallback=fallback)
    location = LocationEventService(store, checker, rikka)
    legacy = LegacyAlertService(config.legacy_state_path, rikka)
    return IngressApplication(config, location, legacy)


def main() -> None:
    logging.basicConfig(
        level=os.environ.get("LC_LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(message)s",
    )
    application = build_application_from_env()
    stop_event = threading.Event()

    def scheduler() -> None:
        while not stop_event.wait(15):
            try:
                application.location_service.run_due_jobs_once()
            except Exception as exc:
                logging.error("scheduler cycle failed error_type=%s", type(exc).__name__)

    worker = threading.Thread(target=scheduler, name="location-event-scheduler", daemon=True)
    worker.start()
    server = ThreadingHTTPServer(
        (application.config.bind_host, application.config.bind_port),
        make_handler(application),
    )
    try:
        server.serve_forever()
    finally:
        stop_event.set()
        server.server_close()


if __name__ == "__main__":
    main()
