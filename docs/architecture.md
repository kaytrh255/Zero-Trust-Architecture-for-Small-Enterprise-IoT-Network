# Architecture (current implementation)

## Architectural style

The project is a modular monolith: one Spring Boot application owns REST APIs, authentication, device management, persistence, and (in later phases) policy evaluation and auditing. PostgreSQL is the only application database. Docker Compose runs the backend and database for local development.

## Current deployment

```text
Postman / curl
      |
      | HTTP on 127.0.0.1:8080
      v
Spring Boot application
  ├── AuthController / AuthService
  ├── Spring Security filter chain / JWT validation
  ├── DeviceController / DeviceService
  ├── repositories / Flyway migrations
  └── demo administrator and device initializer
      |
      | JDBC as a non-superuser application role
      v
PostgreSQL (Docker volume)
```

## Phase 1–3 request paths

Registration and login:

```text
POST /api/auth/register or /api/auth/login
  -> validate request
  -> BCrypt encode or verify password
  -> UserRepository / PostgreSQL users table
  -> for successful login, JwtService signs token
```

Protected request:

```text
Authorization: Bearer <JWT>
  -> JwtAuthenticationFilter verifies signature and expiry
  -> reload current user, role, and enabled state from PostgreSQL
  -> SecurityFilterChain requires authentication
  -> method security checks the endpoint role
  -> controller / service handles the request
```

Device creation:

```text
POST /api/devices (ADMIN token)
  -> method security requires ROLE_ADMIN
  -> validate unique device code and MQTT client ID
  -> associate current administrator as owner
  -> DeviceRepository / PostgreSQL devices table
  -> return DeviceResponse
```

Device status update uses `PATCH /api/devices/{id}/status`; the controller requires `ADMIN`. Deleting a device sets its status to `REVOKED` rather than physically deleting its record.

## Persistence and bootstrap

Flyway migrations `V1__create_users.sql` and `V2__create_devices.sql` define the schema. Hibernate uses `ddl-auto: validate`. At startup a configured bootstrap admin is created only if absent, and demo devices are inserted only if not already present. A configured username already owned by a non-admin causes startup to fail rather than silently elevating that account.

The current implementation stores device states and enforces user roles on management APIs. It does not yet evaluate IoT resource access based on device state; that is a later policy/access-decision phase.
