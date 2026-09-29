# Architecture (current implementation)

## Architectural style

The project is a modular monolith: one Spring Boot application owns REST APIs, authentication, device and policy management, access decisions, audits, and MQTT telemetry ingestion. PostgreSQL is the application database. Docker Compose runs PostgreSQL, a local Mosquitto broker, and the backend.

## Local deployment

```text
curl / Postman                  Simulated IoT publisher
      |                                   |
      | HTTP + bearer JWT                 | MQTT + shared broker login + device token
      v                                   v
Spring Boot application <----------> Mosquitto (127.0.0.1:1883)
  ├── AuthController / AuthService            └── iot/telemetry/{deviceCode}
  ├── JwtAuthenticationFilter
  ├── DeviceController / DeviceService
  ├── PolicyController / PolicyService
  ├── AccessController / ZeroTrustDecisionService
  ├── ProtectedResourceController / ProtectedResourceService
  ├── DeviceCredentialService / AccessAuditService / TelemetryIngestionService
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

GET /api/resources/devices/{deviceCode}/telemetry
  -> derive requester from JWT -> evaluate fixed sensor-data/READ request
  -> DENY: return 403 + decision, never query telemetry
  -> ALLOW: query and return that registered device's samples
```

The API returns a policy decision for the demo request; it is not a reverse proxy or general enforcement layer for arbitrary IoT services. Access checks are attributed to the authenticated user. Policy subject and device state come from the registered device, not the request payload. API requester roles `USER` and `DEVICE` are evaluated; management roles get a recorded business DENY.

## MQTT telemetry path

```text
publisher -> shared local broker login + per-device token -> Mosquitto -> Paho subscriber
  -> validate topic/payload -> match token hash to the topic device code
  -> invalid token: audit unauthenticated DENY; no policy evaluation/storage
  -> ZeroTrustDecisionService(device-telemetry, WRITE)
  -> denied: audit only; allowed: save telemetry and update last_seen_at
```

Mosquitto is bound to host loopback, rejects anonymous clients, and reads a password file generated from `.env`. The backend reconnects and resubscribes through Eclipse Paho. Each device also receives a random bearer token; only a BCrypt hash is stored and create/rotate APIs disclose the token once. The subscriber verifies the token against the device named in the topic before trusting that device context. The broker still uses one shared local account and has no per-device topic ACLs; tokens travel in non-TLS MQTT payloads and can be replayed until rotation.

## Persistence

Flyway migrations `V1__create_users.sql` through `V5__bind_device_credentials.sql` define the schema and demo rules. V4 adds access decision history, accepted telemetry, indexes, foreign keys, and sensor/camera telemetry-write ALLOW examples. V5 adds hashed device credentials and an invalid-credential audit reason. Hibernate uses `ddl-auto: validate`; it does not create tables automatically.
