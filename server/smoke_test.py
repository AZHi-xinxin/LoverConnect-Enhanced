#!/usr/bin/env python3
"""Non-destructive HTTP smoke test for a loopback staging instance."""

import json
import os
import time
import uuid
from urllib import error as urllib_error
from urllib import request as urllib_request


BASE_URL = os.environ.get("LC_SMOKE_URL", "http://127.0.0.1:18790").rstrip("/")
TOKEN = os.environ.get("LC_SMOKE_TOKEN", "")


def request(path, body=None, token=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    req = urllib_request.Request(BASE_URL + path, data=data, headers=headers)
    try:
        with urllib_request.urlopen(req, timeout=3) as response:
            return response.status, json.loads(response.read())
    except urllib_error.HTTPError as exc:
        try:
            return exc.code, json.loads(exc.read())
        finally:
            exc.close()


def main():
    if not TOKEN:
        raise SystemExit("LC_SMOKE_TOKEN is required")
    status, health = request("/health")
    assert status == 200 and health.get("status") == "ok", (status, health)

    session_id = str(uuid.uuid4())
    event_id = str(uuid.uuid4())
    event = {
        "event_id": event_id,
        "type": "zone_exit_confirmed",
        "away_session_id": session_id,
        "zone_id": "smoke",
        "zone_label": "暂存测试",
        "occurred_at": int(time.time() * 1000),
        "distance_bucket": None,
        "reported_override": True,
        "app_version": "smoke-test",
    }
    assert request("/loverconnect/v1/location-events", event, "wrong")[0] == 401

    coordinate_event = dict(event, event_id=str(uuid.uuid4()), latitude=1.0)
    status, rejected = request("/loverconnect/v1/location-events", coordinate_event, TOKEN)
    assert status == 400 and "coordinates" in rejected.get("error", ""), (status, rejected)

    status, accepted = request("/loverconnect/v1/location-events", event, TOKEN)
    assert status == 202 and accepted.get("status") == "accepted", (status, accepted)
    status, duplicate = request("/loverconnect/v1/location-events", event, TOKEN)
    assert status == 200 and duplicate.get("status") == "duplicate", (status, duplicate)
    print("smoke: health/auth/privacy/accept/idempotency OK")


if __name__ == "__main__":
    main()
