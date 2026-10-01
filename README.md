# Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Course project:** MDGS04 Information Security

A modular-monolith prototype demonstrating **Never Trust, Always Verify**. Work is delivered incrementally so each component can be run and understood before the next is added.

## Current implementation

- **Phases 1–4:** Java 21 / Spring Boot, PostgreSQL / Flyway, Docker Compose, user registration/JWT, device management, and exact-match policy CRUD.
- **Phase 5:** authenticated access decisions, device-status validation, explicit DENY precedence, default DENY, access-audit records, and policy-gated MQTT telemetry ingestion.
- **Phase 6:** a policy-gated protected telemetry read route; the JWT supplies requester identity and the path names the target device.
- **Phase 7:** per-device Mosquitto Dynamic Security usernames/passwords and topic ACLs, TLS with a locally generated trusted CA and hostname verification, and monotonic sequence replay protection with audit/database persistence. Device status is evaluated by the backend so status denials remain auditable.
- **Phase 8:** opt-in Java integration coverage for the Compose stack's TLS, broker credentials/ACLs, status and policy denials, default DENY, replay, audit persistence, and protected-resource route; Phase 9 extends it with ownership checks.
- **Phase 9:** protected telemetry reads require the authenticated USER to own the device as well as pass device-status and policy checks. ADMINs can transfer a device to an enabled USER; non-owner denials are audited and never query telemetry.
- **Phase 10:** successful ADMIN ownership transfers are recorded with old/new owner snapshots, actor identity, device, and timestamp; authorized device managers can read each device's transfer history.
- **Phase 11:** actual device-status transitions and policy CREATE/UPDATE/DELETE operations write typed management-audit records with authenticated actor, timestamp, and before/after snapshots in the same transaction; admins/security analysts can read the latest history, including policy history after deletion.
- **Phase 12:** all audit-history APIs support a consistent page envelope, stable newest-first ordering, inclusive timestamp ranges, and event-specific filters; page size is capped at 100 and existing role protections remain in force.
- **Phase 13:** Flyway makes the four then-existing business audit histories append-only; opt-in Compose checks inject database failures to verify mutation/audit rollback and exercise timestamp-tie pagination.
- **Phase 14:** separate PostgreSQL runtime and Flyway migration roles; idempotent startup bootstrapping transfers existing object ownership without discarding volumes, while Compose checks prove runtime DML still works and runtime DDL/trigger changes are denied.
- **Phase 15:** run Flyway in its own one-shot Compose service so migration credentials are not injected into the backend container; the backend starts only after migrations complete.
- **Phase 16:** audit successful and rejected API logins in a separate append-only history; only admins and security analysts can search the paged records, which never contain passwords or JWTs.
- **Phase 17:** throttle login attempts per socket peer address with a bounded in-memory fixed window; excess requests receive HTTP `429` and `Retry-After`, without trusting forwarded-IP headers.
- **Phase 18:** sign MQTT telemetry with a per-device Ed25519 key; the backend verifies signatures before policy/replay evaluation and auditing.
- **Phase 19:** a responsive React/TypeScript control plane for device provisioning, policy management, access/login history, telemetry, and the USER-owned protected telemetry route.
- **Phase 20:** append-only auditing for device credential provisioning and rotation, with actor/timestamp snapshots and SHA-256 public-key fingerprints; MQTT passwords and signing private keys are never written to the audit history.

The protected route and MQTT subscriber enforce decisions on the prototype's simulated resource paths. The backend is not a transparent gateway that intercepts arbitrary IoT network traffic. The web console is a role-aware client for the existing APIs; server-side authorization remains authoritative. There is no physical-device deployment.

## Requirements

- Docker Engine / Docker Desktop
- Docker Compose v2 (`docker compose`)
- `curl` (PowerShell users can use `curl.exe`)
- `mosquitto_pub` for host-side MQTT checks (install the Mosquitto client package)
- Optional for direct backend tests: Java 21 and Maven 3.9+
- Optional for frontend development outside Compose: Node.js 22 and npm

## Run with Docker Compose

From the repository root, create a local environment file if you do not already have one:

```bash
cp .env.example .env
```

Set a JWT signing key in `.env`:

```bash
openssl rand -base64 32
```

Put the generated value after `JWT_SECRET=`. Replace the sample application-runtime, database-admin, database-migration, Dynamic Security administrator, and backend MQTT passwords before using the demo; keep all three PostgreSQL passwords distinct. Keep `.env` private; Git ignores it. Existing `.env` files must add `DB_MIGRATION_USERNAME` and `DB_MIGRATION_PASSWORD` (and the Phase 7 `MQTT_DYNSEC_ADMIN_USERNAME`, `MQTT_DYNSEC_ADMIN_PASSWORD`, `MQTT_BACKEND_USERNAME`, and `MQTT_BACKEND_PASSWORD` values); the old shared `MQTT_USERNAME` / `MQTT_PASSWORD` settings are no longer used. For an existing PostgreSQL volume, keep `POSTGRES_ADMIN_PASSWORD` set to the password that currently authenticates the stored `postgres` administrator; Compose does not rotate an initialized volume's administrator password just because `.env` changed.

Start or rebuild the services:

```bash
docker compose up --build -d
docker compose ps
```

Compose runs PostgreSQL, an idempotent `db-roles-init` step, a separate one-shot Flyway `db-migrate` step, local TLS/Dynamic Security initialization, the loopback-bound Mosquitto TLS broker, a broker-role bootstrap step, the backend API, and a static React frontend served by Nginx. `db-roles-init`, `db-migrate`, `mqtt-init`, and `mqtt-bootstrap` are one-shot setup services and should finish with exit code `0`. Compose orders database role bootstrapping after PostgreSQL is healthy and before backend startup; the one-shot step runs again when the stack is recreated. It creates/reconciles the runtime and migration logins and transfers existing `public` schema, application table/sequence/view, and audit-trigger-function ownership to the migration role in place. Do not delete `postgres_data` to apply this upgrade. The separate `db-migrate` Flyway CLI service uses `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD`; the backend receives only `DB_USERNAME` / `DB_PASSWORD` for runtime DML and does not receive migration credentials. Flyway creates/updates users, devices, policies, authentication/access/ownership/status/policy-change/credential-lifecycle audit history, and telemetry. Migration V6 assigns an initial per-device sequence to existing Phase 6 telemetry; V9 adds device-status and policy-mutation audit tables; V10 makes the four business audit histories append-only; V11 adds append-only authentication-attempt history; V12 adds the nullable per-device MQTT signing public key; V13 adds append-only device credential lifecycle history containing public-key fingerprints only. The generated CA, broker certificate, and TLS private keys are stored under ignored `mosquitto/tls/`; `ca.crt` is the public trust certificate and `ca.key` must remain private.

Check the backend and database:

```bash
curl -i http://localhost:8080/actuator/health
```

A successful response has HTTP `200`, overall `"status":"UP"`, and a database component such as `"db":{"status":"UP"}`. The web console is available at `http://localhost:3000` (or `http://localhost:${FRONTEND_PORT}` if configured); API traffic uses the same-origin Nginx proxy. The frontend, backend, and broker ports are bound to loopback by default.

## Web console (Phase 19)

Open `http://localhost:3000` and sign in with the bootstrap administrator or a registered account. `ADMIN` can provision devices, rotate one-time MQTT/signing credentials, change status/ownership, and manage policies. `SECURITY_ANALYST` can review device posture, policies, telemetry, and audit history. A `USER` gets only the protected telemetry reader; the backend checks policy and device ownership before returning samples. User registration always creates a `USER` account, never an administrator.

The console calls the existing REST API through a same-origin proxy, so CORS is not enabled. JWTs are kept in React memory only (not local/session storage) and are cleared on sign-out or tab reload. Device MQTT passwords and Ed25519 private keys appear only in the one-time provisioning/rotation dialog; copy them into the device secret store before closing it. Role-based navigation is only a usability layer—the backend remains the authorization boundary.

For frontend development outside Compose, start the API stack, then run:

```bash
cd frontend
npm ci
npm run dev
```

Vite listens on `0.0.0.0:5173` and proxies relative `/api` and `/actuator` requests to `http://127.0.0.1:8080` by default. Set `VITE_PROXY_TARGET` to change the server-side proxy target; browser code never calls localhost directly.

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

## Provision a per-device MQTT identity (Phases 6–7 and 18)

List devices to find the ID of a bootstrapped device:

```bash
curl -i http://localhost:8080/api/devices \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

Rotate/provision its broker and signing credentials (replace `1` with the listed ID):

```bash
curl -i -X POST http://localhost:8080/api/devices/1/credentials/rotate \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

The response includes `mqttUsername` (the uppercase device code), a random `mqttPassword`, and the one-time `mqttSigningPrivateKey`. Save both secrets directly in the device's local secret store; never commit or log them. The backend stores only the Ed25519 public key, not the private key or MQTT password. Provisioning responses use `Cache-Control: no-store`. Rotation changes both the broker password and signing key pair, invalidating the old credentials. Phase 6's `deviceToken` field in the telemetry body is retired; after upgrading, rotate each existing device credential to provision its broker account and a signing key. A device code and MQTT client ID cannot be changed after provisioning; create a new device identity instead. Broker accounts stay enabled when a device is `INACTIVE`, `BLOCKED`, or `REVOKED` so authorized-topic messages can reach the backend status check and be audited; they are never stored while status is non-`ACTIVE`.

For a device created with `POST /api/devices`, the broker account and signing key are provisioned immediately; the HTTP response returns the same one-time secrets.

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

## Read a protected resource (Phases 6 and 9)

`GET /api/resources/devices/{deviceCode}/telemetry` is an enforcement point for the demo telemetry resource. It derives requester identity from the JWT, evaluates the fixed `sensor-data` / `READ` context, requires the requester to own the device, and queries telemetry only after all checks allow access. Devices are initially owned by the creating ADMIN. Register and log in as a USER first (public registration creates an enabled `USER`); then an ADMIN can assign a device to that account:

```bash
export DEVICE_ID=1 # replace with the id returned by GET /api/devices
curl -i -X PATCH "http://localhost:8080/api/devices/${DEVICE_ID}/owner" \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE' \
  -H 'Content-Type: application/json' \
  -d '{"ownerUsername":"student1"}'

curl -i http://localhost:8080/api/resources/devices/SENSOR-001/telemetry \
  -H 'Authorization: Bearer PASTE_USER_TOKEN_HERE'
```

`ALLOW` returns HTTP `200` with the access decision and up to 100 samples for that target device. A DENY returns HTTP `403`, includes its reason/audit ID, and returns an empty list without querying telemetry. A non-owner is denied as `DEVICE_NOT_OWNED` when the device is active and policy otherwise allows the read. Explicit `DENY` and no-match default `DENY` are evaluated before ownership; blocked devices remain `DEVICE_NOT_ACTIVE`. `/api/access/check` remains a policy-decision demonstration, not a resource fetch, and does not apply ownership enforcement.

## Publish and inspect MQTT telemetry (Phases 7 and 18)

The Mosquitto listener is bound to `127.0.0.1:8883`, uses verified TLS, and has no plaintext `1883` listener. Host-side MQTT clients must trust `mosquitto/tls/ca.crt`; do not use `--insecure`. Each device authenticates with its own username/password and registered client ID. Mosquitto's device ACL permits publishing only to `iot/telemetry/{same device username}`. The backend subscriber has a separate read-only role for `iot/telemetry/+`.

Use the `mqttUsername`, `mqttPassword`, `mqttClientId`, and one-time `mqttSigningPrivateKey` from the provisioning response. The Ed25519 private key is base64url-encoded PKCS#8 DER. The signed payload is the exact UTF-8 telemetry JSON bytes; the MQTT wire body is a JSON envelope containing unpadded base64url `payload` and `signature` fields. Keep secrets out of source control, shell history, and logs.

This Bash example requires `openssl` and `mosquitto_pub`. Set `MQTT_DEVICE_SIGNING_PRIVATE_KEY` from the provisioning response in a secure environment rather than committing it:

```bash
export MQTT_DEVICE_USERNAME='SENSOR-001'
export MQTT_DEVICE_PASSWORD='PASTE_ONE_TIME_MQTT_PASSWORD_HERE'
export MQTT_DEVICE_SIGNING_PRIVATE_KEY='PASTE_ONE_TIME_BASE64URL_ED25519_PRIVATE_KEY_HERE'
export MQTT_DEVICE_CLIENT_ID='SENSOR-001'
export MQTT_SEQUENCE=1

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
printf '%s' "$MQTT_DEVICE_SIGNING_PRIVATE_KEY" \
  | tr '_-' '/+' | openssl base64 -d -A > "$work_dir/device-key.der"
openssl pkey -inform DER -in "$work_dir/device-key.der" -out "$work_dir/device-key.pem"
printf '{"sequence":%s,"metric":"temperature","value":22.5,"unit":"C"}' "$MQTT_SEQUENCE" \
  > "$work_dir/payload.json"
openssl pkeyutl -sign -rawin -inkey "$work_dir/device-key.pem" \
  -in "$work_dir/payload.json" -out "$work_dir/signature.bin"
payload_b64url="$(openssl base64 -A -in "$work_dir/payload.json" | tr '+/' '-_' | tr -d '=')"
signature_b64url="$(openssl base64 -A -in "$work_dir/signature.bin" | tr '+/' '-_' | tr -d '=')"
printf '{"payload":"%s","signature":"%s"}' "$payload_b64url" "$signature_b64url" \
  > "$work_dir/envelope.json"

mosquitto_pub \
  --cafile mosquitto/tls/ca.crt \
  -h 127.0.0.1 -p "${MQTT_PORT:-8883}" \
  -u "$MQTT_DEVICE_USERNAME" -P "$MQTT_DEVICE_PASSWORD" \
  -i "$MQTT_DEVICE_CLIENT_ID" -q 1 \
  -t "iot/telemetry/${MQTT_DEVICE_USERNAME}" \
  -f "$work_dir/envelope.json"
```

The signature is verified before policy/replay evaluation. Invalid signatures are audited as `DENY` / `INVALID_DEVICE_CREDENTIAL` and do not consume a sequence or persist telemetry. `sequence` is required, positive, and strictly increasing: for current high-water mark `N`, `N+1` may be accepted; `N` or lower is audited as `REPLAYED_MESSAGE`. A QoS 1 publish may be accepted by Mosquitto but later denied by signature, policy, status, or replay checks; MQTT publish success alone does not mean telemetry was persisted.

Read stored samples and access audits with an administrator token:

```bash
curl -i http://localhost:8080/api/telemetry \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'

curl -i http://localhost:8080/api/access/audits \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

Accepted telemetry includes `deviceSequence`. Evaluated policy/status/replay decisions and invalid device signatures appear in `access_audits`. Invalid TLS, username/password, client ID, or device-topic ACL attempts are rejected by Mosquitto before backend ingestion; check `docker compose logs mosquitto`, and do not expect an `access_audits` row for a message the backend never received. Malformed envelopes and telemetry JSON are logged and discarded before policy evaluation.

## Phase 7, 9, 10, 11, and 12 tester checklist

Use an `ADMIN` token for device credential provisioning, status/policy changes, and audit reads; use a `USER` token for protected-resource checks and to verify management-history access is forbidden. Both `ADMIN` and `SECURITY_ANALYST` may read the history endpoints.

| Tester action | Exact expected result |
|---|---|
| Rotate the `SENSOR-001` credentials, connect over TLS with the returned username/password and client ID, sign a telemetry JSON body with `mqttSigningPrivateKey`, and publish the base64url envelope to `iot/telemetry/SENSOR-001`. | TLS and broker authentication succeed; device ACL allows its own topic; the backend verifies Ed25519, evaluates status/policy as `ALLOW`, stores one telemetry row, advances `last_mqtt_sequence`, updates `last_seen_at`, and records an `ALLOW` audit. |
| Change a signed telemetry body's bytes without recomputing its Ed25519 signature. | Backend records `DENY` / `INVALID_DEVICE_CREDENTIAL`; no telemetry is stored, `last_seen_at` is unchanged, and the sequence high-water mark is not consumed. |
| Re-publish the exact same signed sequence, or any lower sequence, with otherwise valid credentials. | Broker accepts the authorized topic publish; backend returns no HTTP response to the publisher, does not insert telemetry or update `last_seen_at`, and records `DENY` / `REPLAYED_MESSAGE` with `messageSequence`. |
| Use `SENSOR-001` credentials to publish to `iot/telemetry/CAMERA-001`. | Mosquitto ACL denies the publish before ingestion; no telemetry row and no database audit are added. Broker log records the denial. |
| Use a wrong MQTT password or wrong client ID. | Broker rejects the connection; no telemetry or access-audit row. Mosquitto logs the authentication failure. |
| Omit `--cafile` or supply an untrusted CA. | TLS verification fails before MQTT authentication; no telemetry or access-audit row. |
| Rotate/provision `SENSOR-002` credentials while it is `BLOCKED`, or mark an already provisioned device `BLOCKED`/`REVOKED`; publish to its own topic with valid credentials. | Broker accepts the scoped publish; the backend records `DENY` / `DEVICE_NOT_ACTIVE`, adds no telemetry, and does not advance the sequence high-water mark. A protected-resource request is also HTTP `403` with an audit ID. |
| Keep the device active but add an enabled `DENY` policy for its `device-telemetry` / `WRITE` action, then publish a new sequence. | Broker ACL allows the device's own topic, but the backend stores no sample and adds `DENY` / `EXPLICIT_DENY`. The sequence high-water mark does not advance. |
| Disable/remove all matching telemetry policies and publish a new sequence. | Broker ACL allows the publish, but backend returns `DENY` / `NO_MATCHING_POLICY`; no telemetry row is written (default DENY). |
| ADMIN assigns an enabled `USER` to `SENSOR-001` with `PATCH /api/devices/{id}/owner`; that owner reads the protected route while the device is ACTIVE and policy allows. | Owner change returns HTTP `200` with updated `ownerUsername`; read returns HTTP `200` and the device's telemetry. A transfer audit records old/new owner, acting ADMIN, device, and timestamp. |
| Repeat the same owner assignment or try to read `/api/devices/{id}/ownership-audits` with a USER token. | Repeating the assignment returns HTTP `200` and creates no duplicate event; USER history access returns HTTP `403`. ADMIN (and SECURITY_ANALYST) can page through transfers, up to 100 per page. |
| Set a device status from `ACTIVE` to `BLOCKED`, then read `GET /api/devices/{id}/status-audits`. | HTTP `200`; one row records `ACTIVE` → `BLOCKED`, the authenticated admin ID/username, and `changedAt`. The device remains blocked, and any access/telemetry check still denies as `DEVICE_NOT_ACTIVE`. |
| Repeat the same `BLOCKED` update, send an invalid status such as `OFFLINE`, then use `DELETE /api/devices/{id}` to revoke it. | Same-status PATCH is HTTP `200` and adds no event; invalid status is HTTP `400` and adds no event; DELETE is HTTP `204` and adds exactly one `BLOCKED` → `REVOKED` event. Repeating DELETE is a no-op. USER history access is HTTP `403`. |
| Create a policy, change its effect or enabled state, and read `GET /api/policies/{id}/audits`. | `CREATE` includes only the after snapshot; `UPDATE` includes before and after snapshots. Each records actor and timestamp. A semantically unchanged update succeeds but creates no event. |
| Try a duplicate policy name or an update that conflicts with another name; then delete a policy and read its history by ID. | Failed mutations return HTTP `409` and add no event. Successful DELETE is HTTP `204` and adds a `DELETE` event with before snapshot; history remains readable after the policy row is gone. USER receives HTTP `403`; ADMIN and SECURITY_ANALYST can read the history. |
| Read a history with `?page=0&size=1`, then add a filter such as `newStatus=BLOCKED`, `operation=UPDATE`, or `decision=DENY&reason=DEVICE_NOT_OWNED`; use `from`/`to` timestamps to narrow it further. | HTTP `200` returns `content`, zero-based page/size, `totalElements`, `totalPages`, and next/previous flags. Results are newest first; filters narrow the count and rows. `size=101`, negative page, malformed enum, or `from` later than `to` returns HTTP `400`. USER still receives `403`. |
| Another USER reads the same active, policy-allowed device. | HTTP `403`, reason `DEVICE_NOT_OWNED`, audit ID present, telemetry array empty; the telemetry query is not run. |
| The owner reads `SENSOR-002` or `CAMERA-001` with the seeded policy set. | HTTP `403`, respectively `DEVICE_NOT_ACTIVE` or `NO_MATCHING_POLICY`, audit ID present, telemetry array empty. Those checks take precedence over ownership. |
| A non-ADMIN tries to change device ownership, or ADMIN names a non-USER/disabled account. | HTTP `403` for the non-ADMIN request; HTTP `400` for an invalid owner account. Existing owner remains unchanged. |

Check sequence and telemetry persistence directly if desired:

```bash
docker compose exec postgres psql -U postgres -d zerotrust -c \
  "SELECT device_code, last_mqtt_sequence, last_seen_at FROM devices ORDER BY device_code;"

docker compose exec postgres psql -U postgres -d zerotrust -c \
  "SELECT device_code, device_sequence, metric, metric_value FROM device_telemetry ORDER BY received_at DESC LIMIT 20;"

docker compose exec postgres psql -U postgres -d zerotrust -c \
  "SELECT device_code, previous_owner_username, new_owner_username, changed_by_username, changed_at FROM device_ownership_audits ORDER BY changed_at DESC LIMIT 20;"

docker compose exec postgres psql -U postgres -d zerotrust -c \
  "SELECT device_code, previous_status, new_status, changed_by_username, changed_at FROM device_status_audits ORDER BY changed_at DESC LIMIT 20;"

docker compose exec postgres psql -U postgres -d zerotrust -c \
  "SELECT policy_id, operation, before_name, after_name, changed_by_username, changed_at FROM policy_change_audits ORDER BY changed_at DESC LIMIT 20;"
```

Every DENY case must leave telemetry unchanged. Broker authentication/topic-ACL failures are distinct from backend policy/status/replay denials: only the latter are database-audited. Existing telemetry was assigned a starting sequence during V6; query `last_mqtt_sequence` before choosing a first message after upgrading.

## Phase 10: run the Compose integration test

The integration test targets an already running local Compose stack. It is skipped by ordinary `mvn test`; opt in by setting `PHASE10_INTEGRATION=true`. It creates uniquely named test users/devices and temporary DENY policies (the policies are removed at the end, while test users/devices and their audit/telemetry rows remain). Run it only against a disposable/local demo database, not production data.

Start the stack using the earlier Compose instructions, then from the repository root export the local environment and run the test:

```bash
set -a
. ./.env
set +a
export PHASE10_INTEGRATION=true
export PHASE10_ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
export PHASE10_ADMIN_PASSWORD="$ADMIN_PASSWORD"
export PHASE10_BASE_URL="http://127.0.0.1:${BACKEND_PORT:-8080}"
export PHASE10_MQTT_BROKER_URI="ssl://127.0.0.1:${MQTT_PORT:-8883}"
export PHASE10_MQTT_CA_FILE="$PWD/mosquitto/tls/ca.crt"
(cd backend && mvn -Dtest=Phase10ComposeIntegrationTest test)
```

The test checks: (1) admin and two USER JWT flows plus health, (2) one-time broker/signing credentials and rotation invalidating the old keys, (3) trusted TLS, client-ID, and cross-device topic ACL behavior, (4) valid Ed25519 telemetry, tamper rejection, replay, status, explicit DENY, and default-DENY MQTT outcomes with audit/persistence checks, (5) ADMIN-only owner transfer and its persisted old/new owner, actor, and timestamp history (including idempotent repeat behavior), and (6) owner ALLOW, non-owner `DEVICE_NOT_OWNED`, explicit policy DENY, blocked-device DENY, and no-match default DENY on the protected resource. All denied resource reads return an audit ID and empty telemetry. Test-created records use `phase10-` / `PHASE10-` prefixes.

Use Java 21 and Maven 3.9+ on the host. To run unit tests without Compose, use `cd backend && mvn test`; the integration test remains disabled unless `PHASE10_INTEGRATION=true`. `.github/workflows/ci.yml` runs the unit suite, starts Compose with per-job local credentials, waits for backend health, runs the Phase 10 MQTT/ownership test and the Phase 11, 13, and 14 audit/database-role tests, adds Maven test reports to the job summary, collects container logs on failure, and removes the ephemeral Compose volumes. The workflow uses no GitHub secrets. Its results are reported on pull requests and pushes to `main` or this development branch.

## Phase 11: test device-status and policy-change history

Phase 11's opt-in Compose integration test exercises the new audit tables and history APIs against a running local stack. It creates one test USER, a test device, and two unique policies; it revokes the test device, deletes the policies after checking the history behavior, and leaves the test user's/device's audit records in the local database. Use a disposable/demo stack, not production data.

```bash
set -a
. ./.env
set +a
export PHASE11_INTEGRATION=true
export PHASE11_ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
export PHASE11_ADMIN_PASSWORD="$ADMIN_PASSWORD"
export PHASE11_BASE_URL="http://127.0.0.1:${BACKEND_PORT:-8080}"
(cd backend && mvn -Dtest=Phase11ComposeIntegrationTest test)
```

Expected outcomes: a real `ACTIVE` → `BLOCKED` transition produces one `device_status_audits` row with the admin actor; repeating `BLOCKED` creates none; invalid `OFFLINE` returns `400` and creates none; revocation creates exactly one `BLOCKED` → `REVOKED` row. A policy CREATE records the after snapshot; an actual UPDATE records both snapshots; a no-op update, duplicate create, and conflicting update do not add rows; DELETE records the before snapshot and its history remains readable after the policy is deleted. ADMIN can read history, while the test USER receives `403`. The test fails if any expected HTTP status, actor, snapshot, event count, or post-delete history is missing.

`.github/workflows/ci.yml` runs this test after the Phase 10 MQTT/ownership test, using an ephemeral Compose stack and generated local passwords. Ordinary unit tests are run with `cd backend && mvn test`; Java 21 and Maven 3.9+ are required when running them on the host.

## Phase 12: filter and page audit history

Phase 12 changes the history responses to a page envelope and adds filters; it does not change authorization, device status enforcement, policy evaluation, or audit creation. All four history APIs default to `page=0&size=100`, accept sizes from 1–100, and sort newest first (timestamp, then ID). `from` and `to` accept inclusive ISO-8601 instants ending in `Z`; invalid ranges and invalid query values return HTTP `400`.

Example requests:

```bash
curl -i 'http://localhost:8080/api/access/audits?decision=DENY&reason=DEVICE_NOT_ACTIVE&page=0&size=20' \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'

curl -i 'http://localhost:8080/api/devices/1/status-audits?newStatus=BLOCKED&from=2026-10-01T00%3A00%3A00Z&page=0&size=10' \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'

curl -i 'http://localhost:8080/api/policies/12/audits?operation=UPDATE&changedByUsername=admin&page=0&size=10' \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

The JSON body contains `content`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`, and `hasPrevious`. Event-specific filters are documented in `docs/api.md`; they use full-string matches; username, device-code, and resource filters ignore case and surrounding whitespace. The Phase 10 and 11 Compose integration tests verify pagination/filtering for access, ownership, status, and policy histories; GitHub Actions runs them on the disposable Compose stack.

## Phase 13: verify audit integrity and rollback

Migration V10 makes access, ownership, status, and policy audit rows append-only for normal row DML: PostgreSQL rejects UPDATE/DELETE operations. Phase 14 gives the application runtime role DML only and moves audit-table ownership to the separate Flyway migration role, so the runtime role cannot alter tables or disable/drop these triggers. This is not tamper-proof storage against the migration role or PostgreSQL administrator. The opt-in integration test uses temporary PostgreSQL triggers to make selected inserts/deletes fail, then checks that the paired device/policy mutation also rolls back. It also forces two real status changes to share a timestamp to verify the ID-descending page tie-break. The test creates a USER, device, policy, and associated audit rows that remain in the local database; run it only on a disposable/demo Compose stack. Temporary failure/timestamp triggers are removed in cleanup.

```bash
set -a
. ./.env
set +a
export PHASE13_INTEGRATION=true
export PHASE13_ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
export PHASE13_ADMIN_PASSWORD="$ADMIN_PASSWORD"
export PHASE13_BASE_URL="http://127.0.0.1:${BACKEND_PORT:-8080}"
(cd backend && mvn -Dtest=Phase13AuditIntegrityComposeIntegrationTest test)
```

Expected checks: a status change, owner transfer, policy create, or policy update whose audit insert fails returns an error and leaves the mutation unchanged; a policy DELETE whose database delete fails also rolls back the already-inserted DELETE event. Direct UPDATE/DELETE attempts against each audit table are rejected and the rows remain readable. The status-history page at `page=0&size=1` returns the larger ID when both events have the same timestamp, and page 1 returns the other event. Authorization and decision behavior are unchanged: the fixture's active device with an unmatched resource returns HTTP `200` with `DENY` / `NO_MATCHING_POLICY`; existing explicit-DENY precedence, `DEVICE_NOT_ACTIVE`, and default DENY still apply. The test needs the Docker Compose CLI because it temporarily runs `psql` inside the local PostgreSQL container; the triggers carry unique test names and are removed during test cleanup.

## Phase 14: verify database-role separation and existing-volume upgrade

The Phase 14 integration test targets the running Compose stack and is opt-in. It checks that the backend container does not receive migration credentials, the runtime role is not privileged or an owner, the separate migration role owns Flyway-managed objects and can run DDL, application policy CRUD still succeeds using the runtime datasource, and runtime connections cannot create/alter tables or disable/drop the append-only triggers. To cover existing volumes, the test creates a fixture table owned by the old runtime role, reruns `db-roles-init`, and verifies ownership and runtime DML/sequence access are migrated in place. The fixture table is removed during cleanup; policy audit history is append-only and remains. Use a disposable/demo stack, not production data. The upgrade does not require deleting `postgres_data`.

```bash
set -a
. ./.env
set +a
export PHASE14_INTEGRATION=true
export PHASE14_ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
export PHASE14_ADMIN_PASSWORD="$ADMIN_PASSWORD"
export PHASE14_BASE_URL="http://127.0.0.1:${BACKEND_PORT:-8080}"
(cd backend && mvn -Dtest=Phase14DatabaseRolesComposeIntegrationTest test)
```

Run the test only after `docker compose up --build -d` has completed successfully, so PostgreSQL role bootstrap, Flyway migrations, and the backend are ready. It also needs the Docker Compose CLI to invoke `psql` and rerun the one-shot role bootstrap inside the existing stack. The bootstrap service is idempotent and also runs automatically before migrations on subsequent stack recreations.

## Phase 15: keep migration credentials out of the backend

Flyway now runs in the one-shot `db-migrate` service using the standalone Flyway CLI image and read-only migration SQL mount. Compose orders startup as PostgreSQL health, role bootstrap, migration, then backend. The backend only receives its DML-only `DB_USERNAME` / `DB_PASSWORD`; it has no `DB_MIGRATION_USERNAME` or `DB_MIGRATION_PASSWORD` environment variables. Verify locally with:

```bash
docker compose exec backend sh -c 'test -z "${DB_MIGRATION_USERNAME+x}" && test -z "${DB_MIGRATION_PASSWORD+x}"'
```

The Phase 14 Compose integration test checks this separation in CI, along with migration ownership, runtime DML, and the existing-volume upgrade.

## Phase 16: audit API login attempts

Each login request admitted to authentication processing records a normalized attempted username, `SUCCESS` or `FAILURE`, a timestamp, and the authenticated user ID only on success. Passwords, bearer tokens, and client IP addresses are not included in audit rows or API responses; the rate limiter holds the peer address in memory only for its short window. The `authentication_attempt_audits` table is append-only. Admins and security analysts can search it at `GET /api/auth/audits` using the common page/date filters plus optional `username` and `outcome` filters; ordinary users cannot read it.

Run the opt-in integration check after `docker compose up --build -d` has completed successfully. It verifies success/failure records, generic 401 behavior, role protection, secret minimization, and database-level append-only enforcement. Use a disposable stack: the check leaves its generated test account and append-only events in the database.

```bash
set -a
. ./.env
set +a
export PHASE16_INTEGRATION=true
export PHASE16_ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
export PHASE16_ADMIN_PASSWORD="$ADMIN_PASSWORD"
export PHASE16_BASE_URL="http://127.0.0.1:${BACKEND_PORT:-8080}"
(cd backend && mvn -Dtest=Phase16AuthenticationAuditComposeIntegrationTest test)
```

## Phase 17: throttle login requests

The backend allows 20 login attempts per socket peer address per 60-second fixed window by default; both values are configurable with `AUTH_LOGIN_RATE_LIMIT_MAX_ATTEMPTS` and `AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS`. A bounded in-memory map tracks peers, and excess requests receive HTTP `429` with `Retry-After`. The limiter uses `HttpServletRequest.getRemoteAddr()` and ignores client-supplied forwarding headers. A blocked request is rejected before password authentication and is not inserted into the auth-audit table, avoiding database-write amplification.

This single-process limiter is appropriate for the local Compose prototype, not a multi-replica deployment or an app behind an unconfigured reverse proxy. Production deployments should use a shared rate-limit store/gateway and only honor forwarding headers from explicitly trusted proxies.

Run the opt-in integration check against a disposable stack after `docker compose up --build -d` has completed successfully. Since earlier tests may already have used the process-local budget, the command restarts the backend first; it then sends valid login requests with varying spoofed `X-Forwarded-For` values and verifies the same socket peer is throttled. The check leaves failed-attempt audit rows.

```bash
set -a
. ./.env
set +a
docker compose restart backend
for attempt in $(seq 1 45); do
  curl --fail --silent "http://127.0.0.1:${BACKEND_PORT:-8080}/actuator/health" >/dev/null && break
  sleep 2
done
curl --fail --silent "http://127.0.0.1:${BACKEND_PORT:-8080}/actuator/health" >/dev/null
export PHASE17_INTEGRATION=true
export PHASE17_IP_MAX_ATTEMPTS="${AUTH_LOGIN_RATE_LIMIT_MAX_ATTEMPTS:-20}"
export PHASE17_WINDOW_SECONDS="${AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS:-60}"
export PHASE17_BASE_URL="http://127.0.0.1:${BACKEND_PORT:-8080}"
(cd backend && mvn -Dtest=Phase17LoginRateLimitComposeIntegrationTest test)
```

## Phase 18: sign MQTT application payloads

New API-provisioned devices receive an Ed25519 key pair. The database stores only the public key; `POST /api/devices` and `POST /api/devices/{id}/credentials/rotate` return the private key once with the MQTT credentials and `Cache-Control: no-store`. Rotation replaces both credentials. Existing database rows without a key must be rotated before they can publish signed telemetry; `mqttSignatureEnabled` in device responses shows whether a key is registered.

The MQTT body is an envelope with unpadded-base64url `payload` and `signature` fields. The decoded `payload` is the exact UTF-8 telemetry JSON byte sequence, and the signature is Ed25519 over those exact bytes. The backend locks the device, verifies the signature against the key bound to the topic's device identity, then parses the payload and applies status, policy, and replay checks. A bad signature is audited as `DENY` / `INVALID_DEVICE_CREDENTIAL`, with no untrusted sequence; it cannot consume the high-water mark or persist telemetry. The signed payload remains capped at 2048 bytes and the full envelope at 4096 bytes.

`Phase10ComposeIntegrationTest` exercises a valid signed publish and modifies signed telemetry bytes to verify rejection. Ordinary backend unit tests run with `cd backend && mvn test`; Phase 10 (including Phase 18), 11, 13, 14, 16, 17, and 20 Compose integration classes are opt-in via `PHASE10_INTEGRATION`, `PHASE11_INTEGRATION`, `PHASE13_INTEGRATION`, `PHASE14_INTEGRATION`, `PHASE16_INTEGRATION`, `PHASE17_INTEGRATION`, and `PHASE20_INTEGRATION`. GitHub Actions runs the complete suite against an ephemeral Compose stack and builds the frontend. See Phases 19–20 below for the console and credential history.

## Phase 19: role-aware web control plane

The React/TypeScript console is served at `http://localhost:3000` by the Compose `frontend` service. It includes a live overview, device identity inventory/provisioning/rotation/status/owner controls, exact-match policy CRUD, access-decision and authentication-attempt ledgers, recent accepted telemetry, and a USER-facing protected telemetry reader. The UI reflects `ADMIN`, `SECURITY_ANALYST`, and `USER` capabilities, while every operation is still checked by Spring Security and the backend service layer.

The browser uses relative `/api` and `/actuator` paths through the same-origin Nginx proxy; no CORS allowlist is needed. Access tokens remain in memory and are not stored in `localStorage` or `sessionStorage`. Provisioning secrets remain only in component memory until the one-time secret dialog is closed. Run `npm ci && npm run build` in `frontend` to check the standalone web build; `npm run dev` starts Vite on port `5173` and proxies API traffic to `http://127.0.0.1:8080` (override with `VITE_PROXY_TARGET`).

## Phase 20: audit device credential lifecycle

Provisioning and credential rotation now create append-only `device_credential_audits` events in the same database transaction as the device-key update. Each record snapshots the device code, operation, authenticated ADMIN actor, timestamp, and SHA-256 fingerprints of the old/new public signing keys. The history never stores an MQTT password, signing private key, or complete public-key encoding. ADMINs and SECURITY_ANALYSTs can read paginated, filtered history at `GET /api/devices/{id}/credential-audits`; USER accounts receive `403`.

The device details modal shows recent issue/rotation events and abbreviated public-key fingerprints. Device provisioning/rotation responses remain `Cache-Control: no-store` and still deliver the password/private key once only. Flyway V13 installs an append-only trigger for the new table. `Phase20DeviceCredentialAuditComposeIntegrationTest` checks secret minimization, role restrictions, filters/pagination, rotation continuity, rollback when the audit insert fails, no-store headers, and UPDATE/DELETE rejection.

## Security locations and limitations

- `JwtAuthenticationFilter` authenticates API callers and reloads current account status/role.
- `AccessController` derives the requester from that principal; `ZeroTrustDecisionService` loads registered device state under a database row lock and combines it with `PolicyEvaluationService` results.
- `ProtectedResourceService` evaluates the fixed telemetry read context before querying PostgreSQL; a DENY response contains no resource data.
- `MqttDynamicSecurityService` uses verified TLS and the dedicated Dynamic Security administrator to provision/rotate device credentials. Broker accounts stay enabled across device-status changes so the backend can audit `DEVICE_NOT_ACTIVE` denials.
- Mosquitto Dynamic Security assigns each device a unique role with one literal publish ACL for `iot/telemetry/{deviceCode}`; the backend subscriber uses a distinct least-privilege account.
- `TelemetryIngestionService` validates payload shape and sequence, then applies the same active-status/policy decision. Accepted sequence advancement and telemetry persistence are atomic; repeated/lower sequences are audited and not stored.
- `LoginRateLimiter` throttles login attempts per socket peer in a bounded in-memory window; `AuthenticationAuditService` records admitted login success/failure events without credential material. `AccessAuditService` persists access decisions; `DeviceOwnershipAuditService`, `DeviceStatusAuditService`, `DeviceCredentialAuditService`, and `PolicyChangeAuditService` persist their respective management histories separately. Authentication, access-decision, ownership, status-change, credential-lifecycle, policy-change, and telemetry history reads are restricted to `ADMIN` and `SECURITY_ANALYST`.
- Device status transitions and policy CREATE/UPDATE/DELETE audits share the transaction with the mutation. No-op status changes and rejected management requests create no change event; failed login attempts are recorded separately. Policy DELETE history remains available by policy ID after the live policy row is removed.
- No role can override an explicit `DENY`; missing policies default to `DENY`.

This is a local prototype, not a production network gateway. The protected route enforces access to this demo telemetry resource only. Ownership is enforced for that route using the authenticated JWT and the device's database owner; `/api/access/check` remains a policy-decision demonstration. MQTT uses per-device broker credentials/ACLs, TLS, Ed25519-signed application payloads, and replay sequence checking. The Phase 19–20 console is a client for these APIs, not a separate enforcement gateway. Credential expiry, automated CA rotation, MFA, hardware-backed key storage, production secret management, and a distributed production rate limiter are not implemented.

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
