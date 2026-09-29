# Architecture (current implementation)

## Architectural style

The project is a modular monolith: one Spring Boot application owns REST APIs, authentication, device and policy management, access decisions, audits, and MQTT telemetry ingestion. PostgreSQL is the application database. Docker Compose runs PostgreSQL, a local Mosquitto broker, and the backend.

## Local deployment

```text
curl / Postman                  Simulated IoT publisher
      |                                   |
      | HTTP + bearer JWT                 | MQTT + shared local credential
      v                                   v
Spring Boot application <----------> Mosquitto (127.0.0.1:1883)
  ├── AuthController / AuthService            └── iot/telemetry/{deviceCode}
  ├── JwtAuthenticationFilter
  ├── DeviceController / DeviceService
  ├── PolicyController / PolicyService
  ├── AccessController / ZeroTrustDecisionService
  ├── AccessAuditService / TelemetryIngestionService
  ├── repositories / Flyway migrations
  └── TelemetryQueryService
      |
      | JDBC as a non-superuser application role
      v
PostgreSQL (Docker volume)
```

## Authentication request path

```text
POST /api/auth/register or /api/auth/login
  -> validate request
  -> BCrypt encode or verify password
  -> UserRepository / PostgreSQL users table
  -> on successful login, JwtService signs a token
```

Protected HTTP requests pass through `JwtAuthenticationFilter`, which validates signature and expiry and reloads current user role/enabled state from PostgreSQL. `SecurityConfig` requires authentication for non-public routes; method-level rules protect management, audit, and telemetry reads.

## Access decision path

```text
POST /api/access/check (valid bearer JWT)
  -> requester identity/role from UserPrincipal; body gives deviceCode/resource/action
  -> ZeroTrustDecisionService locks and loads registered device
  -> reject role mismatch, unknown device, or non-ACTIVE device
  -> PolicyEvaluationService: exact enabled match; explicit DENY before ALLOW
  -> no match => DENY
  -> AccessAuditService writes decision and snapshots
  -> return AccessDecision with audit ID
```

The API returns a policy decision for the demo request; it is not a reverse proxy or general enforcement layer for arbitrary IoT services. Access checks are attributed to the authenticated user. Policy subject and device state come from the registered device, not the request payload. API requester roles `USER` and `DEVICE` are evaluated; management roles get a recorded business DENY.

## MQTT telemetry path

```text
publisher -> authenticated local Mosquitto -> Paho subscriber
  -> validate topic/payload -> derive device code from topic
  -> ZeroTrustDecisionService(device-telemetry, WRITE)
  -> denied: audit only; allowed: save telemetry and update last_seen_at
```

Mosquitto is bound to host loopback, rejects anonymous clients, and reads a password file generated from `.env`. The backend reconnects and resubscribes through Eclipse Paho. The demo uses one shared MQTT account, not per-device credentials or topic ACLs; the topic's device code is not cryptographically bound to a device.

## Persistence

Flyway migrations `V1__create_users.sql`, `V2__create_devices.sql`, `V3__create_policies.sql`, and `V4__add_access_audits_and_telemetry.sql` define the schema and demo rules. V4 adds access decision history, accepted telemetry, indexes, foreign keys, and sensor/camera telemetry-write ALLOW examples. Hibernate uses `ddl-auto: validate`; it does not create tables automatically.
