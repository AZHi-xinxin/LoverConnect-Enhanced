# LoverConnect structured ingress

This dependency-free Python service keeps the legacy `POST /loverconnect/alert`
contract and adds the coordinate-free safety-location endpoint used by
LoverConnect 2.3:

```text
POST /loverconnect/v1/location-events
Authorization: Bearer <LC_INGRESS_TOKEN>
Content-Type: application/json; charset=utf-8
```

## Privacy boundary

- Raw latitude, longitude, coordinates, position, or location objects are
  rejected at the HTTP boundary.
- The SQLite database stores event/session state, zone IDs and labels, decision
  metadata, and evidence **message IDs** only.
- RikkaHub user text is read from the selected conversation branch, evaluated
  in memory, and never written to this service's database or logs.
- The optional model fallback receives only the bounded selected-branch text
  needed for the decision. Leave its three `LC_REPORT_LLM_*` variables unset to
  use the conservative local rules only.

## Event contract

Every event contains `event_id`, `away_session_id` (UUIDs), `type`, `zone_id`,
`zone_label`, and `occurred_at`. Optional fields are `distance_bucket`,
`reported_override`, and `app_version`.

Supported types:

- `zone_exit_confirmed`
- `zone_enter_confirmed`
- `distance_tier_crossed`
- `location_degraded`
- `tracking_paused`
- `offline_trip_summary`
- `report_acknowledged`

Accepted events return HTTP 202. Replaying the same `event_id` returns HTTP 200
with `status=duplicate`; this is a successful idempotent acknowledgement.
Validation errors return HTTP 400, bad/missing bearer credentials return 401,
and request throttling returns 429.

## Reminder behavior

1. A confirmed exit opens an away session and schedules a check after 15
   minutes.
2. The checker reads `GET /api/conversations/{conversation_id}` and examines
   only `node.messages[node.selectIndex]` user messages in the bounded window.
3. Clear first-person plans (for example, “I am going out” or “I will be home
   soon”) count as a report. Negative, cancelled, hypothetical, questioned, or
   third-person statements do not. A model result, when configured, suppresses
   only at confidence `>= 0.85` with an evidence ID present in the input.
4. Reader failures retry twice, then fail open by sending the safety reminder.
5. A distance crossing can cause one final reminder, no sooner than 15 minutes
   after the first. All location notices share a four-per-hour cap.
6. Arrival cancels pending report checks and is announced once. Offline trips
   collapse to one summary instead of replaying stale departure notices.
7. `report_acknowledged` cancels future reminders; if one was already sent, a
   single correction receipt is posted.

Jobs are persisted in SQLite. Atomic leases prevent concurrent schedulers from
sending the same notice twice, and expired leases are recoverable after a crash.

## Test

```bash
cd server
python3 -m unittest -v test_loverconnect_ingress.py
```

The suite covers schema privacy, strict validation, selected-branch parsing,
rule/model decisions, retries, idempotency, arrival/offline/ack flows, rate
limits, concurrent schedulers, the HTTP boundary, and the legacy endpoint.

## Run

Copy `credentials.env.example` to a protected location, replace placeholders,
and restrict it to the service account (`chmod 600`). Then either source it in a
throwaway shell or use the provided systemd unit:

```bash
set -a
. /etc/loverconnect-ingress/credentials.env
set +a
python3 /opt/loverconnect-ingress/loverconnect_ingress.py
```

Health check:

```bash
curl -fsS http://127.0.0.1:8790/health
```

For upgrades, first run this implementation on a loopback-only staging port
with a separate database. Switch the existing 8790 service only after tests and
legacy endpoint regression pass. Keep the old receiver and its state backup
until the Android 2.3 end-to-end test has completed.
