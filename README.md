# Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Course project:** MDGS04 Information Security

A modular-monolith prototype demonstrating **Never Trust, Always Verify**. The project is built phase by phase so each component can be run and understood before the next is added.

## Current implementation

Implemented:

- **Phase 1:** Java 21 / Spring Boot foundation, PostgreSQL, Docker Compose, and database-aware health endpoint.
- **Phase 2:** user registration, BCrypt password hashing, JWT login/validation, and a protected current-user endpoint.

Not implemented yet: device management, the Zero Trust policy engine, access decisions, audit logs, MQTT simulation, and the React dashboard. Do not treat this authentication phase as the complete Zero Trust system.

## Requirements

- Docker Engine / Docker Desktop
- Docker Compose v2 (`docker compose`)
- `curl` (PowerShell users can use `curl.exe`)
- Optional for running tests directly: Java 21 and Maven 3.9+

## Run with Docker Compose

From the repository root, create a local environment file:

```bash
cp .env.example .env
```

The template intentionally leaves `JWT_SECRET` empty. Generate a local key with `openssl rand -base64 32` and put the result after `JWT_SECRET=` in `.env` before starting Compose. If you already have a `.env` from Phase 1, keep its database settings and add `JWT_SECRET` and `JWT_EXPIRATION_SECONDS` from `.env.example`; do not overwrite credentials that you need to keep.

Start the services:

```bash
docker compose up --build -d
docker compose ps
```

Flyway creates the `users` table when the backend starts. To check the backend and database:

```bash
curl -i http://localhost:8080/actuator/health
```

A successful response has HTTP `200`, overall `"status":"UP"`, and a database component such as `"db":{"status":"UP"}`. The backend waits for PostgreSQL's `pg_isready` health check before it starts.

## Configuration

The local `.env` file is ignored by Git. `.env.example` contains local demo database values and an empty `JWT_SECRET` placeholder; no actual signing key is committed.

| Variable | Purpose |
| --- | --- |
| `POSTGRES_DB` | Database name |
| `POSTGRES_ADMIN_PASSWORD` | Local Postgres bootstrap-superuser password |
| `DB_USERNAME` / `DB_PASSWORD` | Dedicated backend database account |
| `JWT_SECRET` | HMAC signing key; set a random value of at least 32 bytes |
| `JWT_EXPIRATION_SECONDS` | JWT lifetime (default `3600`) |
| `POSTGRES_PORT` / `BACKEND_PORT` | Host ports (defaults `5432` / `8080`) |

## Demonstrate registration and JWT authentication

The examples below use a local demo password. Use your own value when testing.

### 1. Register a user

```bash
curl -i -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"student1","password":"StudentPass123!","fullName":"Student One"}'
```

Expected status: `201 Created`. The response contains the user profile and role `USER`, but never the password or its hash. The public registration API does not accept a role, so a caller cannot register themselves as `ADMIN`.

### 2. Log in and copy the access token

```bash
curl -i -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"student1","password":"StudentPass123!"}'
```

The response includes `accessToken`, `tokenType` (`Bearer`), `expiresInSeconds`, and the user profile. Copy the `accessToken` value for the next request.

### 3. Call the protected endpoint

Replace `PASTE_ACCESS_TOKEN_HERE` with the token returned by login:

```bash
curl -i http://localhost:8080/api/auth/me \
  -H 'Authorization: Bearer PASTE_ACCESS_TOKEN_HERE'
```

A valid token returns the current user's profile. Calling `/api/auth/me` without a token, with a malformed token, or with an expired token returns `401 Unauthorized` as JSON. Login with an incorrect username/password also returns `401`; invalid request fields return `400`; duplicate usernames return `409`.

The health endpoint and the registration/login endpoints are public. All other routes currently require a valid bearer token. Role authorities are loaded from the database, but role-restricted management routes are introduced in later phases.

## Authentication design (Phase 2)

- `POST /api/auth/register` validates input, normalizes usernames, hashes passwords with BCrypt, and assigns `USER` by default.
- `POST /api/auth/login` uses Spring Security's `AuthenticationManager` and `DaoAuthenticationProvider` to compare the submitted password with its BCrypt hash. Successful login returns a signed JWT.
- `JwtAuthenticationFilter` validates the token signature and expiry on protected requests, then reloads the user's current role and enabled status from PostgreSQL. Disabled or deleted accounts cannot continue using an otherwise valid token.
- The API is stateless: no server-side login session is created. The JWT signing key is read from `JWT_SECRET`; it is not stored in source code.
- Flyway migration `V1__create_users.sql` creates the `users` table. Hibernate is set to `validate`, not to create tables automatically.

The user table stores `password_hash`, never plaintext passwords. It also stores username, full name, role, enabled status, and timestamps. Authentication failures are not yet written to an audit table; audit logging comes in a later phase.

## Error handling

REST validation and application errors use a JSON structure with timestamp, HTTP status, error code, message, and request path. Security failures return `401` or `403` without exposing internal details.

## Useful commands

View service logs:

```bash
docker compose logs -f backend postgres
```

Stop the services while retaining database data:

```bash
docker compose down
```

Reset the local database volume as well:

```bash
docker compose down -v
```

Run the unit tests directly (requires Java 21 and Maven):

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
│   │   ├── controller/AuthController.java
│   │   ├── dto/
│   │   ├── entity/UserAccount.java
│   │   ├── exception/
│   │   ├── repository/UserRepository.java
│   │   ├── security/
│   │   └── service/AuthService.java
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/V1__create_users.sql
│   ├── src/test/java/com/yak/zerotrust/security/
│   ├── Dockerfile
│   └── pom.xml
├── docs/
│   ├── SDD.md
│   ├── architecture.md
│   ├── api.md
│   └── security-model.md
├── postgres/init/01-create-app-user.sh
├── docker-compose.yml
├── .env.example
└── README.md
```
