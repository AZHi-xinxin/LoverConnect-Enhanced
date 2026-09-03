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
- The service never reads RikkaHub conversations. Report state comes only from
  explicit phone-side structured markers (`reported_override` and
  `report_acknowledged`).
- The RikkaHub adapter is send-only: it posts bounded reminder text and does not
  retrieve conversation bodies, assistant reasoning, or tool content.

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

1. A confirmed exit opens an away session. The phone has already completed
   three consecutive checks, so if it did not attach an explicit one-shot
   report marker, the first reminder is due immediately.
2. The phone's “already reported” action sends `reported_override` or a
   `report_acknowledged` event. Either cancels pending report reminders without
   exposing chat text.
3. The server does not guess whether ordinary conversation text counted as a
   report and has no model fallback.
4. A distance crossing can cause one final reminder, no sooner than 15 minutes
   after the first. All location notices share a four-per-hour cap.
5. Arrival cancels pending report checks and is announced once. Offline trips
   collapse to one summary instead of replaying stale departure notices.
6. `report_acknowledged` cancels future reminders; if one was already sent, a
   single correction receipt is posted.

Jobs are persisted in SQLite. Atomic leases prevent concurrent schedulers from
sending the same notice twice, and expired leases are recoverable after a crash.

## Test

```bash
cd server
python3 -m unittest -v test_loverconnect_ingress.py
```

The suite covers schema privacy, strict validation, phone-side report markers,
retries, idempotency, recovery from a crash between event insertion and job
derivation, arrival/offline/ack flows, rate
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
until the Android 2.4.1 end-to-end test has completed.

### 2.4.1 upgrade checklist

The Android app now performs the three-sample confirmation itself. This server
revision therefore schedules a confirmed departure immediately instead of
adding the former 45-second grace period. The SQLite schema and HTTP paths are
unchanged.

1. Back up the currently deployed Python source and SQLite database without
   changing the live service.
2. Run `python3 -m unittest -v test_loverconnect_ingress.py` against this source,
   then start it on a loopback-only staging port with a separate temporary
   database and placeholder/test credentials.
3. Replace only `loverconnect_ingress.py` in the authorized deployment path;
   keep the existing protected environment file, bearer token and production
   database path unchanged.
4. Restart the authorized ingress service and verify `/health`, the legacy
   `/loverconnect/alert` path, a valid direct `zone_enter_confirmed` event, and
   replay of that same `event_id` returning `status=duplicate` without a second
   notification.
5. Keep the source and database backup until a real Android 2.4.1
   home-to-work/custom-fence transition has produced exactly one notice.

Do not deploy merely by copying the public example environment file: it contains
placeholders and must never replace the protected production configuration.
