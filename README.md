# Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Course project:** MDGS04 Information Security

A modular-monolith prototype demonstrating **Never Trust, Always Verify**. Work is delivered incrementally so each component can be run and understood before the next is added.

## Current implementation

- **Phases 1–4:** Java 21 / Spring Boot, PostgreSQL / Flyway, Docker Compose, user registration/JWT, device management, and exact-match policy CRUD.
- **Phase 5:** authenticated access decisions, device-status validation, explicit DENY precedence, default DENY, access-audit records, and policy-gated MQTT telemetry ingestion.
- **Phase 6:** a real policy-gated protected telemetry read route. The JWT supplies requester identity; the path names the target device. No device-ownership check is implemented.
- **Phase 7:** per-device Mosquitto Dynamic Security usernames/passwords and topic ACLs, TLS with a locally generated trusted CA and hostname verification, and monotonic sequence replay protection with audit/database persistence. Device status is evaluated by the backend so status denials remain auditable.
- **Phase 8:** an opt-in Java integration test exercises the running Compose stack end-to-end: TLS trust, credential rotation/client-ID/topic ACLs, status and policy denials, default DENY, replay, audit persistence, and the protected-resource route.

The protected route and MQTT subscriber enforce decisions on the prototype's simulated resource paths. The backend is not a transparent gateway that intercepts arbitrary IoT network traffic. There is no React dashboard or physical-device deployment.

## Requirements

- Docker Engine / Docker Desktop
- Docker Compose v2 (`docker compose`)
- `curl` (PowerShell users can use `curl.exe`)
- `mosquitto_pub` for host-side MQTT checks (install the Mosquitto client package)
- Optional for unit tests directly: Java 21 and Maven 3.9+

## Run with Docker Compose

From the repository root, create a local environment file if you do not already have one:

```bash
cp .env.example .env
```

Set a JWT signing key in `.env`:

```bash
openssl rand -base64 32
```

Put the generated value after `JWT_SECRET=`. Replace the sample application, PostgreSQL, Dynamic Security administrator, and backend MQTT passwords before using the demo. Keep `.env` private; Git ignores it. Existing `.env` files from Phase 6 need the new `MQTT_DYNSEC_ADMIN_USERNAME`, `MQTT_DYNSEC_ADMIN_PASSWORD`, `MQTT_BACKEND_USERNAME`, and `MQTT_BACKEND_PASSWORD` values; the old shared `MQTT_USERNAME` / `MQTT_PASSWORD` settings are no longer used.

Start or rebuild the services:

```bash
docker compose up --build -d
docker compose ps
```

Compose runs PostgreSQL, an initialization step for local TLS/Dynamic Security, the loopback-bound Mosquitto TLS broker, a broker-role bootstrap step, and the backend. The `mqtt-init` and `mqtt-bootstrap` containers should finish with exit code `0`; they are one-shot setup services. Flyway creates/updates users, devices, policies, audits, and telemetry. Migration V6 assigns an initial per-device sequence to existing Phase 6 telemetry. The generated CA, broker certificate, and private keys are stored under ignored `mosquitto/tls/`; `ca.crt` is the public trust certificate and `ca.key` must remain private.

Check the backend and database:

```bash
curl -i http://localhost:8080/actuator/health
```

A successful response has HTTP `200`, overall `"status":"UP"`, and a database component such as `"db":{"status":"UP"}`.

## Obtain tokens

Log in with the bootstrap administrator configured in `.env` and copy `accessToken` from the response. Use `ADMIN_USERNAME` if you changed it from `admin`:

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"YOUR_ADMIN_PASSWORD"}'
```

Register a normal requester account; public registration always assigns the `USER` role:

```bash
curl -i -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"student1","password":"StudentPass123!","fullName":"Student One"}'
```

Log in as that user to get a token for `/api/access/check` and the protected telemetry resource:

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"student1","password":"StudentPass123!"}'
```

## Manage policies (Phase 4)

All policy routes require a JWT. `ADMIN` can read and manage policies; `SECURITY_ANALYST` can read them. Policy subjects are derived from registered device types during evaluation; clients cannot submit a trusted device type or status in an access-check request.

Seeded example rules include:

- `Sensor Read Data`: `SENSOR`, `sensor-data`, `READ`, `ALLOW`
- `Camera Read Stream`: `CAMERA`, `camera-stream`, `READ`, `ALLOW`
- `Sensor Cannot Write Camera`: `SENSOR`, `camera-stream`, `WRITE`, `DENY`
- `Sensor Publish Telemetry`: `SENSOR`, `device-telemetry`, `WRITE`, `ALLOW`
- `Camera Publish Telemetry`: `CAMERA`, `device-telemetry`, `WRITE`, `ALLOW`

For example, list policies with an administrator token:

```bash
curl -i http://localhost:8080/api/policies \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

Use `/api/policies` `POST`, `PUT /api/policies/{id}`, and `DELETE /api/policies/{id}` to manage rules. Choose a new unique name when creating a policy; for example, `Sensor Read Data Extra` is not one of the seeded names.

## Provision a per-device MQTT identity (Phases 6–7)

List devices to find the ID of a bootstrapped device:

```bash
curl -i http://localhost:8080/api/devices \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

Rotate/provision its broker password (replace `1` with the listed ID):

```bash
curl -i -X POST http://localhost:8080/api/devices/1/credentials/rotate \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

The response includes `mqttUsername` (the uppercase device code) and a random `mqttPassword`, both returned only on create/rotation. Save the password directly in the device's local secret store; do not commit or log it. Mosquitto Dynamic Security authenticates the username/password and fixed `mqttClientId`; PostgreSQL does not store the password. Rotation invalidates the previous broker password. Phase 6's `deviceToken` field in the telemetry body is retired; after upgrading, rotate each existing device credential to provision its broker account. A device code and MQTT client ID cannot be changed after provisioning; create a new device identity instead. Broker accounts stay enabled when a device is `INACTIVE`, `BLOCKED`, or `REVOKED` so authorized-topic messages can reach the backend status check and be audited; they are never stored while status is non-`ACTIVE`.

For a device created with `POST /api/devices`, the broker account is provisioned immediately and the HTTP response has the same credential fields.

## Request an access decision (Phase 5)

`POST /api/access/check` requires a valid bearer token. The authenticated user identity and role are taken from the JWT; the request supplies only the device code, resource, and action:

```bash
curl -i -X POST http://localhost:8080/api/access/check \
  -H 'Authorization: Bearer PASTE_USER_TOKEN_HERE' \
  -H 'Content-Type: application/json' \
  -d '{"deviceCode":"SENSOR-001","resource":"sensor-data","action":"READ"}'
```

The endpoint returns HTTP `200` for an evaluated decision, including a `DENY`; missing/invalid authentication returns `401`. The response includes the outcome, reason, matched policy if any, evaluation time, and audit ID.

Examples to exercise the decision order:

- `SENSOR-001` + `sensor-data` + `READ` → `ALLOW` (`POLICY_ALLOW`).
- `SENSOR-001` + `camera-stream` + `WRITE` → `DENY` (`EXPLICIT_DENY`).
- `SENSOR-002` + `sensor-data` + `READ` → `DENY` (`DEVICE_NOT_ACTIVE`), even though an allow rule exists; `SENSOR-002` is seeded `BLOCKED`.
- An active device with no matching rule → `DENY` (`NO_MATCHING_POLICY`).
- Unknown device code → `DENY` (`DEVICE_NOT_FOUND`).
- `ADMIN` and `SECURITY_ANALYST` accounts are not access-requester roles; their checks return `DENY` (`REQUESTER_ROLE_NOT_ALLOWED`). Use a registered `USER` for this demonstration.

Decision order: requester role, registered device, `ACTIVE` status, matching enabled policy (explicit `DENY` before `ALLOW`), then default `DENY` when there is no match. Device type and status come from PostgreSQL, not the request body.

## Read a protected resource (Phase 6)

`GET /api/resources/devices/{deviceCode}/telemetry` is an enforcement point for the demo telemetry resource. It derives requester identity from the JWT, evaluates the fixed `sensor-data` / `READ` context, and queries telemetry only after `ALLOW`:

```bash
curl -i http://localhost:8080/api/resources/devices/SENSOR-001/telemetry \
  -H 'Authorization: Bearer PASTE_USER_TOKEN_HERE'
```

`ALLOW` returns HTTP `200` with the access decision and up to 100 samples for that target device. A denied decision returns HTTP `403`, includes its reason/audit ID, and returns an empty telemetry list; the telemetry repository is not queried. For example, `SENSOR-002` is blocked and `CAMERA-001` has no `sensor-data` read policy, so both requests must be denied. The path's device code names the target; the JWT supplies requester identity. **There is no device-ownership enforcement.**

## Publish and inspect MQTT telemetry (Phase 7)

The Mosquitto listener is bound to `127.0.0.1:8883`, uses verified TLS, and has no plaintext `1883` listener. Host-side MQTT clients must trust `mosquitto/tls/ca.crt`; do not use `--insecure`. Each device authenticates with its own username/password and registered client ID. Mosquitto's device ACL permits publishing only to `iot/telemetry/{same device username}`. The backend subscriber has a separate read-only role for `iot/telemetry/+`.

Using the `mqttUsername`, `mqttPassword`, and `mqttClientId` from the credential response, publish a message. Set a new, increasing sequence for every new accepted sample:

```bash
export MQTT_DEVICE_USERNAME='SENSOR-001'
export MQTT_DEVICE_PASSWORD='PASTE_ONE_TIME_MQTT_PASSWORD_HERE'
export MQTT_DEVICE_CLIENT_ID='SENSOR-001'
export MQTT_SEQUENCE=1

mosquitto_pub \
  --cafile mosquitto/tls/ca.crt \
  -h 127.0.0.1 -p "${MQTT_PORT:-8883}" \
  -u "$MQTT_DEVICE_USERNAME" -P "$MQTT_DEVICE_PASSWORD" \
  -i "$MQTT_DEVICE_CLIENT_ID" -q 1 \
  -t "iot/telemetry/${MQTT_DEVICE_USERNAME}" \
  -m "{\"sequence\":${MQTT_SEQUENCE},\"metric\":\"temperature\",\"value\":22.5,\"unit\":\"C\"}"
```

The message body has no token. `sequence` is required and positive. For the currently accepted high-water mark `N`, sequence `N+1` can be accepted; sequence `N` or lower is audited as `DENY` / `REPLAYED_MESSAGE` and is not stored. A QoS 1 publish may be accepted by Mosquitto but later denied by the application's policy/replay decision; MQTT publish success alone does not mean the telemetry was persisted.

Read stored samples and access audits with an administrator token:

```bash
curl -i http://localhost:8080/api/telemetry \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'

curl -i http://localhost:8080/api/access/audits \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

Accepted telemetry includes `deviceSequence`. Evaluated policy/status/replay decisions appear in `access_audits`. Invalid TLS, username/password, client ID, or device-topic ACL attempts are rejected by Mosquitto before backend ingestion; check `docker compose logs mosquitto`, and do not expect an `access_audits` row for a message the backend never received. Malformed payloads are logged and discarded before decision evaluation.

## Phase 7 tester checklist

Use an `ADMIN` token for device credential provisioning, status/policy changes, and audit reads; use a `USER` token for the protected-resource checks.

| Tester action | Exact expected result |
|---|---|
| Rotate the `SENSOR-001` broker password, then connect over TLS with the returned username/password and client ID; publish sequence `1` to `iot/telemetry/SENSOR-001` (or a number above its reported database high-water mark). | TLS and broker authentication succeed; device ACL allows its own topic; backend evaluates status/policy as `ALLOW`; one telemetry row is added, `last_mqtt_sequence` advances, `last_seen_at` updates, and an `ALLOW` audit row is added. |
| Re-publish the exact same sequence, or any lower sequence, with otherwise valid credentials/payload. | Broker accepts the authorized topic publish; backend returns no HTTP response to the publisher, does not insert telemetry or update `last_seen_at`, and records `DENY` / `REPLAYED_MESSAGE` with `messageSequence`. |
| Use `SENSOR-001` credentials to publish to `iot/telemetry/CAMERA-001`. | Mosquitto ACL denies the publish before ingestion; no telemetry row and no database audit are added. Broker log records the denial. |
| Use a wrong MQTT password or wrong client ID. | Broker rejects the connection; no telemetry or access-audit row. Mosquitto logs the authentication failure. |
| Omit `--cafile` or supply an untrusted CA. | TLS verification fails before MQTT authentication; no telemetry or access-audit row. |
| Rotate/provision `SENSOR-002` credentials while it is `BLOCKED`, or mark an already provisioned device `BLOCKED`/`REVOKED`; publish to its own topic with valid credentials. | Broker accepts the scoped publish; the backend records `DENY` / `DEVICE_NOT_ACTIVE`, adds no telemetry, and does not advance the sequence high-water mark. A protected-resource request is also HTTP `403` with an audit ID. |
| Keep the device active but add an enabled `DENY` policy for its `device-telemetry` / `WRITE` action, then publish a new sequence. | Broker ACL allows the device's own topic, but the backend stores no sample and adds `DENY` / `EXPLICIT_DENY`. The sequence high-water mark does not advance. |
| Disable/remove all matching telemetry policies and publish a new sequence. | Broker ACL allows the publish, but backend returns `DENY` / `NO_MATCHING_POLICY`; no telemetry row is written (default DENY). |
| `USER` reads `/api/resources/devices/SENSOR-001/telemetry`. | HTTP `200` only when status and policy allow; data is for the requested target device. |
| `USER` reads `/api/resources/devices/SENSOR-002/telemetry` or `CAMERA-001` with the seeded policy set. | HTTP `403`, respectively `DEVICE_NOT_ACTIVE` or `NO_MATCHING_POLICY`, audit ID present, telemetry array empty. This path does not enforce device ownership. |

Check sequence and telemetry persistence directly if desired:

```bash
docker compose exec postgres psql -U postgres -d zerotrust -c \
  "SELECT device_code, last_mqtt_sequence, last_seen_at FROM devices ORDER BY device_code;"

docker compose exec postgres psql -U postgres -d zerotrust -c \
  "SELECT device_code, device_sequence, metric, metric_value FROM device_telemetry ORDER BY received_at DESC LIMIT 20;"
```

Every DENY case must leave telemetry unchanged. Broker authentication/topic-ACL failures are distinct from backend policy/status/replay denials: only the latter are database-audited. Existing telemetry was assigned a starting sequence during V6; query `last_mqtt_sequence` before choosing a first message after upgrading.

## Phase 8: run the Compose integration test

The integration test targets an already running local Compose stack. It is skipped by ordinary `mvn test`; opt in by setting `PHASE8_INTEGRATION=true`. It creates uniquely named test users/devices and a temporary DENY policy (the policy is removed at the end, while test users/devices and their audit/telemetry rows remain). Run it only against a disposable/local demo database, not production data.

Start the stack using the earlier Compose instructions, then from the repository root export the local environment and run the test:

```bash
set -a
. ./.env
set +a
export PHASE8_INTEGRATION=true
export PHASE8_ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
export PHASE8_ADMIN_PASSWORD="$ADMIN_PASSWORD"
export PHASE8_BASE_URL="http://127.0.0.1:${BACKEND_PORT:-8080}"
export PHASE8_MQTT_BROKER_URI="ssl://127.0.0.1:${MQTT_PORT:-8883}"
export PHASE8_MQTT_CA_FILE="$PWD/mosquitto/tls/ca.crt"
(cd backend && mvn -Dtest=Phase8ComposeIntegrationTest test)
```

The test checks: (1) admin and USER JWT flows plus health, (2) one-time per-device credentials and rotation invalidating the old password, (3) trusted TLS succeeds while the system-default untrusted CA, wrong client ID, and a cross-device topic fail, (4) ALLOW, replay, blocked-device, explicit policy DENY, and ACTUATOR no-match/default-DENY MQTT outcomes and audit rows, (5) denied messages do not change `last_seen_at` or persist telemetry, and (6) a non-owner USER can read the target's telemetry when policy allows, while blocked and no-matching-policy reads return HTTP 403, an audit ID, and an empty list. Test-created users/devices are identifiable by the `phase8-` / `PHASE8-` prefixes.

Use Java 21 and Maven 3.9+ on the host. To run unit tests without Compose, use `cd backend && mvn test`; the end-to-end test remains disabled unless `PHASE8_INTEGRATION=true`. `.github/workflows/ci.yml` runs the unit suite, starts Compose with per-job local credentials, waits for backend health, runs this integration test, collects container logs on failure, and removes the ephemeral Compose volumes. The workflow uses no GitHub secrets. Its results are reported on pull requests and pushes to `main` or this development branch.

## Security locations and limitations

- `JwtAuthenticationFilter` authenticates API callers and reloads current account status/role.
- `AccessController` derives the requester from that principal; `ZeroTrustDecisionService` loads registered device state under a database row lock and combines it with `PolicyEvaluationService` results.
- `ProtectedResourceService` evaluates the fixed telemetry read context before querying PostgreSQL; a DENY response contains no resource data.
- `MqttDynamicSecurityService` uses verified TLS and the dedicated Dynamic Security administrator to provision/rotate device credentials. Broker accounts stay enabled across device-status changes so the backend can audit `DEVICE_NOT_ACTIVE` denials.
- Mosquitto Dynamic Security binds each per-device username to the `iot/telemetry/%u` publish ACL; the backend subscriber uses a distinct least-privilege account.
- `TelemetryIngestionService` validates payload shape and sequence, then applies the same active-status/policy decision. Accepted sequence advancement and telemetry persistence are atomic; repeated/lower sequences are audited and not stored.
- `AccessAuditService` persists decisions; audit/telemetry read endpoints are restricted to `ADMIN` and `SECURITY_ANALYST`.
- No role can override an explicit `DENY`; missing policies default to `DENY`.

This is a local prototype, not a production network gateway. The protected route enforces access to this demo telemetry resource only. In HTTP, the JWT identifies the requester and the path names the target device; there is no ownership check. MQTT uses per-device broker credentials/ACLs, TLS, and replay sequence checking, but no signed application payloads, credential expiry, automated CA rotation, MFA, rate limiting, policy-change/API-authentication auditing, or production secret management.

## Useful commands

View service logs:

```bash
docker compose logs -f backend postgres mosquitto
```

Stop services while retaining database and broker data:

```bash
docker compose down
```

Reset the local database and broker security configuration (this deletes local database/broker data):

```bash
docker compose down -v
```

Run unit tests directly (requires Java 21 and Maven):

```bash
cd backend
mvn test
```

## Documentation

- [Software Design Description](docs/SDD.md)
- [Architecture](docs/architecture.md)
- [API specification](docs/api.md)
- [Security model](docs/security-model.md)
