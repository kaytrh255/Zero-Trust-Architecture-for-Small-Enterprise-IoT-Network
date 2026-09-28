# Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Course project:** MDGS04 Information Security

A modular-monolith prototype demonstrating the principle **Never Trust, Always Verify**. The project is being built in phases so that each component can be run and verified before the next is added.

## Current implementation: Phase 1

Phase 1 contains only the Spring Boot foundation, PostgreSQL, Docker Compose, and a database-aware health endpoint. Authentication, users, devices, policies, MQTT, audit records, and the React dashboard are **not implemented yet**.

The backend uses Java 21, Spring Boot 3.5, Maven, Spring Data JPA, and PostgreSQL. The database health status is provided by Spring Boot Actuator at `GET /actuator/health`.

## Requirements

- Docker Engine
- Docker Compose v2 (`docker compose`)
- `curl` for the health check

Java 21 and Maven 3.9+ are only needed if you want to run the backend directly on the host rather than build it with Docker.

## Run with Docker Compose

From the repository root, create a local environment file and start the Phase 1 services:

```bash
cp .env.example .env
docker compose up --build -d
```

Wait until PostgreSQL is healthy, then inspect both services:

```bash
docker compose ps
```

The backend waits for PostgreSQL's `pg_isready` health check before starting. Verify that the application and its database connection are up:

```bash
curl -i http://localhost:8080/actuator/health
```

A successful response has HTTP `200` and JSON with an overall `"status":"UP"` and a `"db":{"status":"UP"}` component. Component order may vary. This database component is the runtime verification that the backend can obtain a working PostgreSQL connection.

To view startup logs:

```bash
docker compose logs -f backend postgres
```

Stop the services while keeping the database volume:

```bash
docker compose down
```

To also delete the local database data and start fresh next time:

```bash
docker compose down -v
```

## Configuration

Copy `.env.example` to `.env`. The example values are for local development only; `.env` is ignored by Git. Change the values in `.env` as needed.

| Variable | Purpose | Example |
| --- | --- | --- |
| `POSTGRES_DB` | Database created by the Postgres container | `zerotrust` |
| `POSTGRES_ADMIN_PASSWORD` | Local Postgres container's bootstrap-superuser password | local-only sample value |
| `DB_USERNAME` | Dedicated non-superuser account used by the backend | `zerotrust_app` |
| `DB_PASSWORD` | Password for the backend database account | local-only sample value |
| `POSTGRES_PORT` | Host port mapped to Postgres | `5432` |
| `BACKEND_PORT` | Host port mapped to Spring Boot | `8080` |

The database URL inside Compose uses the service name `postgres`, not `localhost`. On first database initialization, `postgres/init/01-create-app-user.sh` creates a dedicated non-superuser account for the backend and grants it database connection plus schema usage/create privileges. The bootstrap superuser is not used by the application. Both published ports bind to `127.0.0.1` for local development. The application can also be configured for a host-run backend with `SPRING_DATASOURCE_URL` and `SERVER_PORT` environment variables.

## Phase 1 architecture

```text
Host browser / curl
        |
        | HTTP :8080
        v
Spring Boot backend (Java 21)
        |
        | JDBC / PostgreSQL driver
        v
PostgreSQL (:5432, persistent Docker volume)
```

The Compose health dependency prevents the backend container from starting before PostgreSQL accepts connections. Actuator's health endpoint then checks the configured `DataSource`. The backend connects with a non-superuser account; the separate bootstrap account is used only by Postgres initialization. No application tables or migrations are created in this phase; JPA schema generation is explicitly disabled until a later phase adds the model and migrations.

## Repository structure (Phase 1)

```text
.
├── backend/
│   ├── src/main/java/com/yak/zerotrust/ZeroTrustApplication.java
│   ├── src/main/resources/application.yml
│   ├── Dockerfile
│   └── pom.xml
├── postgres/init/01-create-app-user.sh
├── docker-compose.yml
├── .env.example
└── README.md
```

## Next phases

After Phase 1 is verified, the next planned step is authentication (BCrypt, JWT, and protected endpoints). Device management, the default-deny policy engine, audit logging, MQTT simulation, and the frontend will be added in later phases.
