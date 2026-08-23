import json
import sqlite3
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from urllib import error as urllib_error
from urllib import request as urllib_request

from loverconnect_ingress import (
    AppConfig,
    IngressApplication,
    JOB_INITIAL_CHECK,
    LegacyAlertService,
    LocationEventService,
    ReportDecision,
    RikkaClient,
    RuleFirstReportChecker,
    SQLiteStore,
    ThreadingHTTPServer,
    UserTextMessage,
    ValidationError,
    make_handler,
    validate_location_event,
)


class MutableClock:
    def __init__(self, now_ms=1_800_000_000_000):
        self.now_ms = now_ms

    def __call__(self):
        return self.now_ms

    def advance(self, milliseconds):
        self.now_ms += milliseconds


class FakeNotifier:
    def __init__(self, succeed=True):
        self.succeed = succeed
        self.messages = []

    def send_text(self, text):
        self.messages.append(text)
        return self.succeed


class BlockingNotifier(FakeNotifier):
    def __init__(self):
        super().__init__()
        self.started = threading.Event()
        self.release = threading.Event()
        self._lock = threading.Lock()

    def send_text(self, text):
        with self._lock:
            self.messages.append(text)
        self.started.set()
        self.release.wait(timeout=2)
        return True


class FakeReader:
    def __init__(self, messages=None, fail=False):
        self.messages = list(messages or [])
        self.fail = fail
        self.calls = []

    def selected_user_messages(self, start_ms, end_ms, max_messages=20, char_cap=6000):
        self.calls.append((start_ms, end_ms, max_messages, char_cap))
        if self.fail:
            raise RuntimeError("unavailable")
        return [item for item in self.messages if start_ms <= item.created_at_ms <= end_ms]


class FakeFallback:
    def __init__(self, result):
        self.result = result

    def classify(self, messages):
        return dict(self.result)


def event_body(clock, event_type="zone_exit_confirmed", session_id=None, **overrides):
    body = {
        "event_id": str(uuid.uuid4()),
        "type": event_type,
        "away_session_id": session_id or str(uuid.uuid4()),
        "zone_id": "home",
        "zone_label": "家",
        "occurred_at": clock(),
        "distance_bucket": None,
        "reported_override": False,
        "app_version": "2.3.0",
    }
    body.update(overrides)
    return body


class IngressTestCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.clock = MutableClock()
        self.store = SQLiteStore(Path(self.temp.name) / "events.db")
        self.reader = FakeReader()
        self.notifier = FakeNotifier()
        self.checker = RuleFirstReportChecker(self.reader)

    def tearDown(self):
        self.temp.cleanup()

    def service(self, **kwargs):
        return LocationEventService(
            self.store,
            kwargs.pop("checker", self.checker),
            kwargs.pop("notifier", self.notifier),
            clock=self.clock,
            **kwargs,
        )

    def test_raw_coordinates_and_unknown_fields_are_rejected(self):
        body = event_body(self.clock, latitude=30.0)
        with self.assertRaisesRegex(ValidationError, "coordinates"):
            validate_location_event(body, self.clock())
        body = event_body(self.clock, metadata={"position": [1, 2]})
        with self.assertRaisesRegex(ValidationError, "coordinates"):
            validate_location_event(body, self.clock())
        body = event_body(self.clock, surprise=True)
        with self.assertRaisesRegex(ValidationError, "unsupported field"):
            validate_location_event(body, self.clock())

    def test_selected_branch_reader_uses_only_selected_user_text(self):
        now = self.clock()
        data = {
            "messages": [
                {
                    "id": "node-1",
                    "selectIndex": 1,
                    "messages": [
                        {"id": "wrong", "role": "USER", "createdAt": now, "parts": [{"type": "text", "text": "错误分支"}]},
                        {"id": "chosen", "role": "USER", "createdAt": now, "parts": [{"type": "text", "text": "选中分支"}, {"type": "image", "url": "x"}]},
                    ],
                },
                {
                    "id": "node-2",
                    "selectIndex": 0,
                    "messages": [
                        {"id": "assistant", "role": "ASSISTANT", "createdAt": now, "parts": [{"type": "text", "text": "不应读取"}]}
                    ],
                },
            ]
        }
        result = RikkaClient.parse_selected_user_messages(data, now - 1, now + 1)
        self.assertEqual([(item.message_id, item.text) for item in result], [("chosen", "选中分支")])

    def test_rules_are_conservative_and_negative_phrases_override(self):
        now = self.clock()
        reader = FakeReader([UserTextMessage("n1", "我还没出门，不是我出门了", now)])
        decision = RuleFirstReportChecker(reader).check(now - 1000, now + 1000)
        self.assertFalse(decision.reported)
        reader.messages.append(UserTextMessage("p1", "我刚出门了，晚点联系", now + 1))
        decision = RuleFirstReportChecker(reader).check(now - 1000, now + 1000)
        self.assertTrue(decision.reported)
        self.assertEqual(decision.evidence_message_id, "p1")

    def test_clear_first_person_plan_counts_but_cancellation_or_question_does_not(self):
        now = self.clock()
        planned = RuleFirstReportChecker(
            FakeReader([UserTextMessage("p1", "我一会儿回家", now)])
        ).check(now - 1, now + 1)
        self.assertTrue(planned.reported)
        self.assertEqual(planned.evidence_message_id, "p1")

        cancelled = RuleFirstReportChecker(
            FakeReader([UserTextMessage("n1", "我本来准备出门，但现在不出门了", now)])
        ).check(now - 1, now + 1)
        self.assertFalse(cancelled.reported)

        questioned = RuleFirstReportChecker(
            FakeReader([UserTextMessage("q1", "我一会儿出门吗？", now)])
        ).check(now - 1, now + 1)
        self.assertFalse(questioned.reported)

    def test_model_requires_threshold_and_valid_evidence_id(self):
        now = self.clock()
        reader = FakeReader([UserTextMessage("m1", "模糊表达", now)])
        low = RuleFirstReportChecker(
            reader,
            FakeFallback({"reported": True, "confidence": 0.84, "evidence_message_id": "m1"}),
        ).check(now - 1, now + 1)
        self.assertFalse(low.reported)
        invalid = RuleFirstReportChecker(
            reader,
            FakeFallback({"reported": True, "confidence": 0.99, "evidence_message_id": "other"}),
        ).check(now - 1, now + 1)
        self.assertFalse(invalid.reported)
        accepted = RuleFirstReportChecker(
            reader,
            FakeFallback({"reported": True, "confidence": 0.85, "evidence_message_id": "m1"}),
        ).check(now - 1, now + 1)
        self.assertTrue(accepted.reported)
        self.assertEqual(accepted.source, "model")

    def test_reported_override_suppresses_and_duplicate_is_idempotent(self):
        service = self.service(initial_grace_ms=0)
        body = event_body(self.clock, reported_override=True)
        status, first = service.accept(body)
        self.assertEqual(status, 202)
        self.assertEqual(self.notifier.messages, [])
        session = self.store.session(body["away_session_id"])
        self.assertEqual(session["reported"], 1)
        status, second = service.accept(body)
        self.assertEqual((status, second["status"]), (200, "duplicate"))

    def test_rikka_rule_report_suppresses_initial_alert(self):
        now = self.clock()
        self.reader.messages = [UserTextMessage("report-1", "我已经出门了", now)]
        service = self.service(initial_grace_ms=0)
        body = event_body(self.clock)
        service.accept(body)
        self.assertEqual(self.notifier.messages, [])
        session = self.store.session(body["away_session_id"])
        self.assertEqual(session["reported"], 1)
        self.assertEqual(session["evidence_message_id"], "report-1")

    def test_reader_failure_retries_twice_then_fails_open(self):
        self.reader.fail = True
        service = self.service(initial_grace_ms=0)
        body = event_body(self.clock)
        service.accept(body)
        self.assertEqual(self.notifier.messages, [])
        self.clock.advance(60_001)
        service.run_due_jobs_once()
        self.assertEqual(self.notifier.messages, [])
        self.clock.advance(120_001)
        service.run_due_jobs_once()
        self.assertEqual(len(self.notifier.messages), 1)
        self.assertIn("离开", self.notifier.messages[0])

    def test_arrival_cancels_departure_and_sends_once(self):
        service = self.service()
        session_id = str(uuid.uuid4())
        service.accept(event_body(self.clock, session_id=session_id))
        arrival = event_body(
            self.clock,
            "zone_enter_confirmed",
            session_id=session_id,
            zone_id="work",
            zone_label="工作",
        )
        service.accept(arrival)
        self.assertEqual(len(self.notifier.messages), 1)
        self.assertIn("工作", self.notifier.messages[0])
        service.accept(event_body(self.clock, "zone_enter_confirmed", session_id=session_id, zone_id="work", zone_label="工作"))
        self.assertEqual(len(self.notifier.messages), 1)

    def test_offline_trip_summary_replaces_stale_departure(self):
        service = self.service()
        session_id = str(uuid.uuid4())
        service.accept(event_body(self.clock, session_id=session_id))
        summary = event_body(
            self.clock,
            "offline_trip_summary",
            session_id=session_id,
            zone_id="work",
            zone_label="工作",
        )
        service.accept(summary)
        self.clock.advance(20 * 60_000)
        service.run_due_jobs_once()
        self.assertEqual(len(self.notifier.messages), 1)
        self.assertIn("离线期间", self.notifier.messages[0])
        self.assertIn("工作", self.notifier.messages[0])

    def test_post_alert_acknowledgement_sends_one_correction(self):
        service = self.service(initial_grace_ms=0)
        session_id = str(uuid.uuid4())
        service.accept(event_body(self.clock, session_id=session_id))
        self.assertEqual(len(self.notifier.messages), 1)
        service.accept(event_body(self.clock, "report_acknowledged", session_id=session_id))
        self.assertEqual(len(self.notifier.messages), 2)
        self.assertIn("补充报备", self.notifier.messages[-1])
        service.accept(event_body(self.clock, "report_acknowledged", session_id=session_id))
        self.assertEqual(len(self.notifier.messages), 2)

    def test_pre_alert_acknowledgement_is_silent(self):
        service = self.service()
        session_id = str(uuid.uuid4())
        service.accept(event_body(self.clock, session_id=session_id))
        service.accept(event_body(self.clock, "report_acknowledged", session_id=session_id))
        self.clock.advance(20 * 60_000)
        service.run_due_jobs_once()
        self.assertEqual(self.notifier.messages, [])

    def test_pause_and_degraded_events_are_diagnostic_only(self):
        service = self.service(initial_grace_ms=0)
        for kind in ("tracking_paused", "location_degraded"):
            status, result = service.accept(event_body(self.clock, kind))
            self.assertEqual((status, result["status"]), (202, "accepted"))
        self.assertEqual(self.notifier.messages, [])
        with self.store._connect() as conn:
            statuses = [
                row[0]
                for row in conn.execute(
                    "SELECT status FROM location_events ORDER BY received_at_ms, rowid"
                )
            ]
        self.assertEqual(statuses, ["diagnostic_only", "diagnostic_only"])

    def test_distance_second_alert_waits_and_is_sent_once(self):
        service = self.service(initial_grace_ms=0, second_delay_ms=15 * 60_000)
        session_id = str(uuid.uuid4())
        service.accept(event_body(self.clock, session_id=session_id))
        self.assertEqual(len(self.notifier.messages), 1)
        distance = event_body(
            self.clock,
            "distance_tier_crossed",
            session_id=session_id,
            distance_bucket="5_to_10km",
        )
        service.accept(distance)
        self.assertEqual(len(self.notifier.messages), 1)
        self.clock.advance(15 * 60_000 + 1)
        service.run_due_jobs_once()
        self.assertEqual(len(self.notifier.messages), 2)
        self.assertIn("最后一次", self.notifier.messages[-1])
        service.accept(event_body(self.clock, "distance_tier_crossed", session_id=session_id, distance_bucket="over_10km"))
        self.assertEqual(len(self.notifier.messages), 2)

    def test_global_location_delivery_limit_is_four_per_hour(self):
        service = self.service()
        for index in range(5):
            service.accept(
                event_body(
                    self.clock,
                    "zone_enter_confirmed",
                    session_id=str(uuid.uuid4()),
                    zone_id=f"z{index}",
                    zone_label=f"区域{index}",
                )
            )
        self.assertEqual(len(self.notifier.messages), 4)
        self.clock.advance(3_600_002)
        service.run_due_jobs_once()
        self.assertEqual(len(self.notifier.messages), 5)

    def test_concurrent_schedulers_claim_a_notice_only_once(self):
        notifier = BlockingNotifier()
        service_a = self.service(notifier=notifier, initial_grace_ms=1_000)
        service_b = self.service(notifier=notifier, initial_grace_ms=1_000)
        service_a.accept(event_body(self.clock))
        self.clock.advance(1_001)

        first = threading.Thread(target=service_a.run_due_jobs_once)
        second = threading.Thread(target=service_b.run_due_jobs_once)
        first.start()
        self.assertTrue(notifier.started.wait(timeout=1))
        second.start()
        second.join(timeout=1)
        self.assertFalse(second.is_alive())
        notifier.release.set()
        first.join(timeout=1)

        self.assertEqual(len(notifier.messages), 1)

    def test_database_schema_contains_no_text_or_coordinate_columns(self):
        forbidden = {"text", "body", "content", "latitude", "longitude", "lat", "lng", "coordinates"}
        for columns in self.store.table_columns().values():
            self.assertTrue(forbidden.isdisjoint(columns), columns)

    def test_legacy_contract_preserves_duplicate_and_cooldown_semantics(self):
        seconds = lambda: self.clock() / 1000
        legacy = LegacyAlertService(Path(self.temp.name) / "legacy.json", self.notifier, seconds)
        base = {
            "event_id": "legacy-event-1",
            "type": "app_timeout",
            "timestamp": seconds(),
            "app_package": "com.example.app",
            "app_label": "Example",
            "duration_minutes": 30,
        }
        self.assertEqual(legacy.accept(base)[0], 202)
        self.assertEqual(legacy.accept(base), (200, {"status": "duplicate"}))
        other = dict(base, event_id="legacy-event-2")
        self.assertEqual(legacy.accept(other), (429, {"status": "cooldown"}))


class HttpBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.clock = MutableClock()
        store = SQLiteStore(Path(self.temp.name) / "events.db")
        notifier = FakeNotifier()
        checker = RuleFirstReportChecker(FakeReader())
        location = LocationEventService(store, checker, notifier, clock=self.clock)
        legacy = LegacyAlertService(Path(self.temp.name) / "legacy.json", notifier, self.clock)
        config = AppConfig("test-token-value-123456", "127.0.0.1", 0, str(Path(self.temp.name) / "events.db"), str(Path(self.temp.name) / "legacy.json"))
        app = IngressApplication(config, location, legacy)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(app))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def post(self, path, body, token="test-token-value-123456", content_type="application/json; charset=utf-8"):
        req = urllib_request.Request(
            self.url + path,
            data=json.dumps(body).encode("utf-8"),
            method="POST",
            headers={"Authorization": "Bearer " + token, "Content-Type": content_type},
        )
        try:
            with urllib_request.urlopen(req, timeout=2) as response:
                return response.status, json.loads(response.read())
        except urllib_error.HTTPError as exc:
            try:
                return exc.code, json.loads(exc.read())
            finally:
                exc.close()

    def test_auth_content_type_and_health(self):
        with urllib_request.urlopen(self.url + "/health", timeout=2) as response:
            self.assertEqual(response.status, 200)
        body = event_body(self.clock)
        self.assertEqual(self.post("/loverconnect/v1/location-events", body, token="wrong")[0], 401)
        self.assertEqual(self.post("/loverconnect/v1/location-events", body, content_type="text/plain")[0], 415)
        self.assertEqual(self.post("/loverconnect/v1/location-events", body)[0], 202)


if __name__ == "__main__":
    unittest.main()
