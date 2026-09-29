# Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Course project:** MDGS04 Information Security

A modular-monolith prototype demonstrating **Never Trust, Always Verify**. The project is built phase by phase so each component can be run and understood before the next is added.

## Current implementation

Implemented:

- **Phase 1:** Java 21 / Spring Boot foundation, PostgreSQL, Docker Compose, and database-aware health endpoint.
- **Phase 2:** registration, BCrypt password hashing, JWT login/validation, and protected current-user endpoint.
- **Phase 3:** device persistence, role-protected device CRUD, and status management.
- **Phase 4:** policy persistence/CRUD, seeded example policies, and deterministic policy selection.

Not implemented yet: the end-to-end Zero Trust access decision API, access audit logs, MQTT simulation, and React dashboard. Phase 4 can select a matching policy, but policies are not yet enforced on IoT resource requests; Phase 5 will connect identity, device status, resource/action, and default-deny decisions.

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

The template leaves `JWT_SECRET` empty. Generate a local key with `openssl rand -base64 32` and put it after `JWT_SECRET=` in `.env` before starting Compose. If you already have a `.env` from a previous phase, keep its database/JWT/admin settings; do not overwrite credentials you need to keep.

Start or rebuild the services:

```bash
docker compose up --build -d
docker compose ps
```

Flyway creates/updates the `users`, `devices`, and `policies` tables. The migrations add the three example policies on first application. The backend also bootstraps a local administrator and demo devices if they are missing.

Check the backend and database:

```bash
curl -i http://localhost:8080/actuator/health
```

A successful response has HTTP `200`, overall `"status":"UP"`, and a database component such as `"db":{"status":"UP"}`.

## Obtain a bearer token

Log in with the bootstrap administrator configured in `.env` and copy `accessToken` from the response:

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"YOUR_ADMIN_PASSWORD"}'
```

If you changed `ADMIN_USERNAME`, use that value instead of `admin`. You can also register a normal user with `POST /api/auth/register`; public registration always assigns `USER`.

## Policy API (Phase 4)

All policy routes require a valid JWT. `ADMIN` can read and manage policies; `SECURITY_ANALYST` can read them. Public registration creates only `USER` accounts.

### List policies

```bash
curl -i http://localhost:8080/api/policies \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

The seeded policies are:

- `Sensor Read Data`: `SENSOR`, `sensor-data`, `READ`, `ALLOW`
- `Camera Read Stream`: `CAMERA`, `camera-stream`, `READ`, `ALLOW`
- `Sensor Cannot Write Camera`: `SENSOR`, `camera-stream`, `WRITE`, `DENY`

### Create a policy

```bash
curl -i -X POST http://localhost:8080/api/policies \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Sensor Read Data Extra","subject":"SENSOR","resource":"sensor-data","action":"READ","effect":"ALLOW","enabled":true,"description":"Allow sensors to read sensor data"}'
```

Use a new policy name for this example because the seeded name is already present; for example, `Sensor Read Data Extra`. Actions are `READ`, `WRITE`, `EXECUTE`; effects are `ALLOW`, `DENY`. The response contains the policy ID, which can be used for `GET /api/policies/{id}`, `PUT /api/policies/{id}`, and `DELETE /api/policies/{id}`.

A regular `USER` token receives `403 Forbidden` for the policy list. Duplicate policy names return `409`; invalid fields return `400`; missing IDs return `404`.

## What the Phase 4 policy evaluator does

`PolicyEvaluationService` retrieves enabled policies matching the exact subject, resource, and action. An explicit matching `DENY` is selected before a matching `ALLOW`; if there is no match, the service returns no policy. Phase 5 will turn that result into an `AccessDecision` and apply default DENY to protected requests. **There is not yet a public `/api/access/check` endpoint, and a stored policy does not itself grant or block IoT traffic in this phase.**

## Authentication and device design

- `POST /api/auth/register` validates input, normalizes usernames, hashes passwords with BCrypt, and assigns `USER` by default.
- `POST /api/auth/login` uses Spring Security's `AuthenticationManager` and `DaoAuthenticationProvider` to return a signed JWT.
- `JwtAuthenticationFilter` validates token signature/expiry and reloads the account's current role and enabled state from PostgreSQL.
- Device reads allow `ADMIN` and `SECURITY_ANALYST`; device changes require `ADMIN`. Device owner is assigned from the authenticated admin. `DELETE /api/devices/{id}` marks the device `REVOKED` rather than deleting it.
- The `devices` and `policies` tables are created by Flyway migrations V2 and V3. Hibernate uses `ddl-auto: validate`.

The `users` table stores `password_hash`, never plaintext passwords. Device status is stored and manageable, but its effect on IoT access is not enforced until Phase 5.

## Error handling

REST validation and application errors use JSON with timestamp, HTTP status, error code, message, and request path. Missing/invalid authentication returns `401`; an authenticated role lacking access returns `403`; duplicate names/identifiers return `409`; missing records return `404`.

## Useful commands

View service logs:

```bash
docker compose logs -f backend postgres
```

Stop services while retaining database data:

```bash
docker compose down
```

Reset the local database volume as well (this deletes local data):

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
│   │   ├── controller/
│   │   ├── dto/
│   │   ├── entity/
│   │   ├── exception/
│   │   ├── policy/
│   │   ├── repository/
│   │   ├── security/
│   │   └── service/
│   ├── src/main/resources/db/migration/
│   │   ├── V1__create_users.sql
│   │   ├── V2__create_devices.sql
│   │   └── V3__create_policies.sql
│   ├── src/test/java/com/yak/zerotrust/
│   ├── Dockerfile
│   └── pom.xml
├── docs/
├── postgres/init/01-create-app-user.sh
├── docker-compose.yml
├── .env.example
└── README.md
```
