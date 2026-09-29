# Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Course project:** MDGS04 Information Security

A modular-monolith prototype demonstrating **Never Trust, Always Verify**. The project is built phase by phase so each component can be run and understood before the next is added.

## Current implementation

Implemented:

- **Phase 1:** Java 21 / Spring Boot foundation, PostgreSQL, Docker Compose, and database-aware health endpoint.
- **Phase 2:** registration, BCrypt password hashing, JWT login/validation, and protected current-user endpoint.
- **Phase 3:** device persistence, status management, role-protected device CRUD, and local demo data.

Not implemented yet: Zero Trust resource policies and access decisions, access audit logs, MQTT simulation, and the React dashboard. Device statuses are stored and manageable now, but **blocked/revoked device access is not enforced until the policy/access-decision phase**.

## Requirements

- Docker Engine / Docker Desktop
- Docker Compose v2 (`docker compose`)
- `curl` (PowerShell users can use `curl.exe`)
- Optional for unit tests directly: Java 21 and Maven 3.9+

## Run with Docker Compose

From the repository root, create a local environment file:

```bash
cp .env.example .env
```

The template intentionally leaves `JWT_SECRET` empty. Generate a local key with `openssl rand -base64 32` and put it after `JWT_SECRET=` in `.env` before starting Compose.

If you already have a `.env` from Phase 2, keep its database/JWT settings and add the `ADMIN_USERNAME`, `ADMIN_FULL_NAME`, and `ADMIN_PASSWORD` values from `.env.example`. Change the sample admin password for your own demo. If you previously registered `admin` as a normal `USER`, set `ADMIN_USERNAME` to a different name (for example, `zt-admin`); the bootstrap code will not silently promote an existing user.

Start the services:

```bash
docker compose up --build -d
docker compose ps
```

Flyway creates the `users` and `devices` tables. At startup the backend creates a bootstrap `ADMIN` account and three demo devices if they do not already exist. The bootstrap password is hashed with BCrypt. Changing `ADMIN_PASSWORD` after the administrator already exists does not reset that account's password.

Check the backend and database:

```bash
curl -i http://localhost:8080/actuator/health
```

A successful response has HTTP `200`, overall `"status":"UP"`, and a database component such as `"db":{"status":"UP"}`. The backend waits for PostgreSQL's `pg_isready` health check before it starts.

## Configuration

The local `.env` file is ignored by Git. `.env.example` contains local demo values and an empty JWT signing-key placeholder; no actual JWT key is committed.

| Variable | Purpose |
| --- | --- |
| `POSTGRES_DB` | Database name |
| `POSTGRES_ADMIN_PASSWORD` | Local Postgres bootstrap-superuser password |
| `DB_USERNAME` / `DB_PASSWORD` | Dedicated backend database account |
| `JWT_SECRET` | HMAC signing key; generate a random value of at least 32 bytes |
| `JWT_EXPIRATION_SECONDS` | JWT lifetime (default `3600`) |
| `ADMIN_USERNAME` / `ADMIN_FULL_NAME` | Initial administrator identity |
| `ADMIN_PASSWORD` | Initial administrator password; used only when that account is first created |
| `POSTGRES_PORT` / `BACKEND_PORT` | Host ports (defaults `5432` / `8080`) |

## Demonstrate authentication and device authorization

### 1. Register a regular user

```bash
curl -i -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"student1","password":"StudentPass123!","fullName":"Student One"}'
```

Expected status: `201 Created`. Public registration always assigns `USER`; the request cannot select `ADMIN`.

### 2. Log in as the bootstrap administrator

Use the `ADMIN_USERNAME` and `ADMIN_PASSWORD` values from your `.env`:

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"YOUR_ADMIN_PASSWORD"}'
```

Copy `accessToken` from the response. It is a bearer token with a configured expiry.

### 3. List the demo devices

Replace `PASTE_ADMIN_TOKEN_HERE` with the login token:

```bash
curl -i http://localhost:8080/api/devices \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE'
```

On a fresh database, the list contains `SENSOR-001` (`ACTIVE`), `CAMERA-001` (`ACTIVE`), and `SENSOR-002` (`BLOCKED`). The device IDs are returned in the response; use the appropriate ID in the following requests.

### 4. Change a device status

```bash
curl -i -X PATCH http://localhost:8080/api/devices/1/status \
  -H 'Authorization: Bearer PASTE_ADMIN_TOKEN_HERE' \
  -H 'Content-Type: application/json' \
  -d '{"status":"BLOCKED"}'
```

Expected status: `200 OK`, with the updated device returned. Valid statuses are `ACTIVE`, `INACTIVE`, `BLOCKED`, and `REVOKED`.

### 5. Verify role restrictions

Log in as `student1` and call `GET /api/devices` with that user's token. Expected status: `403 Forbidden`. The list/detail operations allow `ADMIN` and `SECURITY_ANALYST`; create/update/status-change/delete operations require `ADMIN`.

`DELETE /api/devices/{id}` intentionally marks a device `REVOKED` instead of physically deleting the row, preserving its identity for later audit/history work.

## Authentication and device design

- `POST /api/auth/register` validates input, normalizes usernames, hashes passwords with BCrypt, and assigns `USER` by default.
- `POST /api/auth/login` uses Spring Security's `AuthenticationManager` and `DaoAuthenticationProvider`; successful login returns a signed JWT.
- `JwtAuthenticationFilter` validates token signature and expiry, then reloads the user's current role and enabled status from PostgreSQL.
- The API is stateless. The JWT signing key is supplied through `JWT_SECRET`, not Java source code.
- Device creation assigns the authenticated administrator as owner. Device code and MQTT client ID are unique. Flyway migration `V2__create_devices.sql` creates the device table.
- Hibernate uses `ddl-auto: validate`; Flyway owns schema creation.

The `users` table stores `password_hash`, never plaintext passwords. The `devices` table stores device identity, type, network address, MQTT client ID, current status, owner, creation time, and last-seen time. `lastSeenAt` remains empty until telemetry integration is implemented.

## Error handling

REST validation and application errors use JSON with timestamp, HTTP status, error code, message, and request path. Missing/invalid authentication returns `401`; an authenticated role lacking access returns `403`; duplicate identifiers return `409`; missing records return `404`.

## Useful commands

View service logs:

```bash
docker compose logs -f backend postgres
```

Stop services while retaining database data:

```bash
docker compose down
```

Reset the local database volume as well:

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
│   │   ├── repository/
│   │   ├── security/
│   │   └── service/
│   ├── src/main/resources/db/migration/
│   │   ├── V1__create_users.sql
│   │   └── V2__create_devices.sql
│   ├── src/test/java/com/yak/zerotrust/
│   ├── Dockerfile
│   └── pom.xml
├── docs/
├── postgres/init/01-create-app-user.sh
├── docker-compose.yml
├── .env.example
└── README.md
```
