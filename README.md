# Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Course project:** MDGS04 Information Security

A modular-monolith prototype demonstrating **Never Trust, Always Verify**. The project is built incrementally so each component can be run and understood before the next is added.

## Current implementation

- **Phase 1:** Java 21 / Spring Boot foundation, PostgreSQL, Docker Compose, and database-aware health endpoint.
- **Phase 2:** user registration, BCrypt password hashing, JWT login/validation, and protected current-user endpoint.
- **Phase 3:** device persistence, role-protected CRUD, and device status management.
- **Phase 4:** policy persistence/CRUD, seeded rules, and deterministic exact-match policy selection.
- **Phase 5:** authenticated access checks, device-status enforcement, default-deny decisions, access audit records, and policy-gated MQTT telemetry ingestion.
- **Phase 6:** one-time, per-device MQTT tokens; token rotation; rejection/auditing of unauthenticated telemetry; and a protected telemetry resource that returns data only after an ALLOW decision.

The protected route and telemetry subscriber enforce decisions on the prototype's simulated resource paths. The backend is not a transparent gateway that intercepts arbitrary IoT network traffic. There is no React dashboard or physical-device deployment.

## Requirements

- Docker Engine / Docker Desktop
- Docker Compose v2 (`docker compose`)
- `curl` (PowerShell users can use `curl.exe`)
- Optional for unit tests directly: Java 21 and Maven 3.9+

## Run with Docker Compose

From the repository root, create a local environment file if you do not already have one:

```bash
cp .env.example .env
```

Set a local signing key in `.env` before starting Compose:

```bash
openssl rand -base64 32
```

Put the generated value after `JWT_SECRET=`. Replace all sample passwords, including `MQTT_PASSWORD`, before using the demo. Keep `.env` private; it is ignored by Git. If you already have a `.env` from an earlier phase, add the `MQTT_USERNAME`, `MQTT_PASSWORD`, and `MQTT_PORT` settings without overwriting credentials you need to retain.

Start or rebuild the services:

```bash
docker compose up --build -d
docker compose ps
```

Compose runs PostgreSQL, a loopback-bound Mosquitto broker, and the backend. Flyway migrations create/update the users, devices (including per-device credential hashes), policies, access-audit, and telemetry tables. The backend bootstraps a local administrator and demo devices if they are missing.

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

Log in as that user to get a token for `/api/access/check`:

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

## Provision an MQTT device credential (Phase 6)

Creating a device returns a random `deviceToken` once. For a bootstrapped device, get its ID as an administrator:

```bash
curl -i http://localhost:8080/api/devices \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

Then rotate that device's token (replace `1` with its ID from the list):

```bash
curl -i -X POST http://localhost:8080/api/devices/1/credentials/rotate \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

The response contains the safe device summary and the new `deviceToken`. Store it in the device's local secret store; the backend stores only a BCrypt hash and cannot show the token again. Rotating it immediately invalidates the previous token. Never commit the returned token or paste it into logs/issues.

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
- `ADMIN` and `SECURITY_ANALYST` accounts manage or inspect the system but are not access-requester roles; their checks return `DENY` (`REQUESTER_ROLE_NOT_ALLOWED`). Use a registered `USER` for the API demonstration.

Decision order: requester role, registered device, `ACTIVE` status, matching enabled policy (explicit `DENY` before `ALLOW`), then default `DENY` when there is no match. Device type and status come from PostgreSQL, not the request body.

## Read a protected resource (Phase 6)

`GET /api/resources/devices/{deviceCode}/telemetry` is a real enforcement point for the demo telemetry resource. It derives requester identity from the JWT, evaluates the fixed `sensor-data` / `READ` context, and queries telemetry only after `ALLOW`:

```bash
curl -i http://localhost:8080/api/resources/devices/SENSOR-001/telemetry \
  -H 'Authorization: Bearer PASTE_USER_TOKEN_HERE'
```

`ALLOW` returns HTTP `200` with the access decision and up to 100 samples for that device. A denied decision returns HTTP `403`, includes its reason/audit ID, and returns an empty telemetry list; the telemetry repository is not queried. For example, `SENSOR-002` is blocked and `CAMERA-001` has no `sensor-data` read policy, so both requests must be denied. This is a sample API resource, not enforcement on arbitrary devices or network traffic.

## Inspect access audits

Every evaluated access check, MQTT policy decision, and syntactically valid MQTT message with an invalid device token writes an `access_audits` row. `ADMIN` and `SECURITY_ANALYST` can retrieve the latest 100 records:

```bash
curl -i http://localhost:8080/api/access/audits \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

The record includes the requester/channel, device snapshot, resource/action, outcome/reason, matched policy snapshot, and evaluation timestamp. Malformed MQTT topics/payloads rejected before credential verification are logged by the subscriber but do not create access-audit rows. API authentication attempts and policy-management changes are not yet audited.

## Publish and inspect MQTT telemetry (Phase 6)

The Mosquitto listener is bound to `127.0.0.1` and still uses the shared local broker username/password from `.env` to protect the local broker. In addition, each device must present its own one-time `deviceToken` in the telemetry JSON. Obtain/rotate that token using the device-credential endpoint above. The backend subscribes to `iot/telemetry/+`.

Set the device token you just provisioned, then publish:

```bash
export DEVICE_TOKEN='PASTE_SENSOR_001_DEVICE_TOKEN_HERE'
docker compose exec -e DEVICE_TOKEN="$DEVICE_TOKEN" mosquitto sh -c 'mosquitto_pub -h localhost -p 1883 -u "$MQTT_USERNAME" -P "$MQTT_PASSWORD" -t iot/telemetry/SENSOR-001 -m "{\"deviceToken\":\"$DEVICE_TOKEN\",\"metric\":\"temperature\",\"value\":22.5,\"unit\":\"C\"}"'
```

The backend checks that the token hash matches the registered device named by the topic, then evaluates `device-telemetry` / `WRITE`. Only an authenticated, `ACTIVE` device with an enabled `ALLOW` policy can store the sample. Invalid device tokens create a DENY audit record; malformed topics/payloads are logged and discarded. Denied messages never become telemetry, and `last_seen_at` is updated only after an accepted sample. `ADMIN` and `SECURITY_ANALYST` can view the latest 100 samples:

```bash
curl -i http://localhost:8080/api/telemetry \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

## Phase 6 tester checklist

Use an authenticated `USER` token for the protected-resource checks and an `ADMIN` token for credential rotation/audit reads.

| Tester action | Expected result |
|---|---|
| Rotate the token for `SENSOR-001`, then publish a valid telemetry JSON using that token to `iot/telemetry/SENSOR-001`. | MQTT ingestion is ALLOW; telemetry is visible under `GET /api/telemetry`; `last_seen_at` advances. |
| Publish a syntactically valid message with a wrong 43-character token. | No telemetry row is written; the audit contains `DENY` / `INVALID_DEVICE_CREDENTIAL` and requester `mqtt:unauthenticated`. |
| Publish with `SENSOR-001`'s token to `iot/telemetry/CAMERA-001`. | Credential/topic mismatch is denied and audited; no camera telemetry is stored. |
| Rotate the token again, then retry the previously valid token. | Old token is denied; only the new token works. |
| `USER` reads `/api/resources/devices/SENSOR-001/telemetry`. | HTTP `200`, `ALLOW`, and only that device's samples. |
| `USER` reads the route for `SENSOR-002` or `CAMERA-001`. | HTTP `403`, respectively `DEVICE_NOT_ACTIVE` or `NO_MATCHING_POLICY`, audit ID present, telemetry array empty. |

In all DENY cases, verify no new sample appears in `GET /api/telemetry`. The HTTP decision endpoint `/api/access/check` still returns business DENY with HTTP `200`; the protected resource route instead returns HTTP `403` and no data.

## Security locations and limitations

- `JwtAuthenticationFilter` authenticates API callers and reloads current account status/role.
- `AccessController` derives the requester from that principal; `ZeroTrustDecisionService` loads registered device state under a database row lock and combines it with `PolicyEvaluationService` results.
- `ProtectedResourceService` evaluates the fixed telemetry read context before querying PostgreSQL; a DENY response contains no resource data.
- `DeviceCredentialService` issues random per-device tokens, stores BCrypt hashes, and authenticates the topic's claimed device code against its registered credential. Create/rotate responses disclose the token once only.
- `TelemetryIngestionService` checks the device token and then the same status/policy decision before persistence; invalid credentials are audited, and invalid or denied messages are not stored.
- `AccessAuditService` persists decisions; audit/telemetry read endpoints are restricted to `ADMIN` and `SECURITY_ANALYST`.
- No role can override an explicit `DENY`; missing policies default to `DENY`.

This is a local prototype, not a production network gateway. The protected route enforces access to this demo telemetry resource only. The JWT identifies the API requester; the path's device code names the target resource and is not the requester identity. MQTT uses a per-device bearer token at the application ingestion layer, but clients still use one shared local broker login and Mosquitto does not enforce per-device topic ACLs. The token travels in the telemetry payload over local, non-TLS MQTT and can be replayed until rotated. TLS, broker-side per-device MQTT credentials/ACLs, message signing/replay protection, policy-change/API-authentication auditing, token expiry, rate limiting, and the dashboard remain future work.

## Useful commands

View service logs:

```bash
docker compose logs -f backend postgres mosquitto
```

Stop services while retaining database data:

```bash
docker compose down
```

Reset the local database and MQTT volumes as well (this deletes local data):

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

## Repository structure

```text
.
├── backend/
│   ├── src/main/java/com/yak/zerotrust/
│   │   ├── access/
│   │   ├── controller/
│   │   ├── dto/
│   │   ├── entity/
│   │   ├── exception/
│   │   ├── mqtt/
│   │   ├── policy/
│   │   ├── repository/
│   │   ├── security/
│   │   └── service/
│   ├── src/main/resources/db/migration/V1__...sql through V5__...sql
│   ├── src/test/java/com/yak/zerotrust/
│   ├── Dockerfile
│   └── pom.xml
├── docs/
├── mosquitto/config/mosquitto.conf
├── postgres/init/01-create-app-user.sh
├── docker-compose.yml
├── .env.example
└── README.md
```
