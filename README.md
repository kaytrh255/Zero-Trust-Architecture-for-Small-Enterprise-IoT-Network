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

The access-check API and telemetry subscriber apply these decisions to the prototype's simulated request/telemetry paths. The backend is not yet a transparent gateway that intercepts arbitrary IoT network traffic. There is no React dashboard or physical-device deployment.

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

Compose runs PostgreSQL, a loopback-bound Mosquitto broker, and the backend. The database migrations create/update the users, devices, policies, access-audit, and telemetry tables. The backend bootstraps a local administrator and demo devices if they are missing.

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

## Inspect access audits

Every valid access-check or MQTT decision writes an `access_audits` row, including denied checks. `ADMIN` and `SECURITY_ANALYST` can retrieve the latest 100 records:

```bash
curl -i http://localhost:8080/api/access/audits \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

The record includes the requester/channel, device snapshot, resource/action, outcome/reason, matched policy snapshot, and evaluation timestamp. Invalid MQTT payloads rejected before evaluation are logged by the subscriber but do not create access-audit rows. Authentication attempts and policy-management changes are not yet audited.

## Publish and inspect MQTT telemetry

The Mosquitto listener is bound to `127.0.0.1` and requires the shared local broker username/password from `.env`. The backend subscribes to `iot/telemetry/+`. A publisher can run inside the broker container, which already has the local credentials in its environment:

```bash
docker compose exec mosquitto sh -c 'mosquitto_pub -h localhost -p 1883 -u "$MQTT_USERNAME" -P "$MQTT_PASSWORD" -t iot/telemetry/SENSOR-001 -m "{\"metric\":\"temperature\",\"value\":22.5,\"unit\":\"C\"}"'
```

The backend validates the topic and bounded JSON payload, evaluates a `WRITE` against `device-telemetry`, and stores the sample only when the device is `ACTIVE` and an enabled `ALLOW` policy matches. Denied MQTT attempts are audited but not stored as telemetry. `last_seen_at` is updated on accepted samples. `ADMIN` and `SECURITY_ANALYST` can view the latest 100 samples:

```bash
curl -i http://localhost:8080/api/telemetry \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

## Security locations and limitations

- `JwtAuthenticationFilter` authenticates API callers and reloads current account status/role.
- `AccessController` derives the requester from that principal; `ZeroTrustDecisionService` loads device state under a database row lock and combines it with `PolicyEvaluationService` results.
- `TelemetryIngestionService` uses the same decision service before persistence; invalid or denied messages do not become stored telemetry.
- `AccessAuditService` persists decisions; audit/telemetry read endpoints are restricted to `ADMIN` and `SECURITY_ANALYST`.
- No role can override an explicit `DENY`; missing policies default to `DENY`.

This is a local prototype, not a production network gateway. API requests use an authenticated user but the device code is a simulation context rather than a per-device cryptographic credential. MQTT currently uses one shared local broker credential, so it is not yet bound to individual device identities. TLS, per-device MQTT credentials/ACLs, message signing, enforcement on arbitrary protected IoT resources, policy-change/authentication auditing, token revocation, rate limiting, and the dashboard remain future work.

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
│   ├── src/main/resources/db/migration/V1__...sql through V4__...sql
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
