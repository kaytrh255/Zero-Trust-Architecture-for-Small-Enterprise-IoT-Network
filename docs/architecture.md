# Architecture (current implementation)

## Architectural style

The project is a modular monolith: one Spring Boot application owns REST APIs, authentication, device and policy management, access decisions, audits, and MQTT telemetry ingestion. PostgreSQL is the application database. Docker Compose runs PostgreSQL, a local Mosquitto broker, the backend, and a separate static React/TypeScript console served by Nginx.

## Local deployment

```text
Browser (React SPA)                        Simulated IoT publisher
      |                                           |
      | same-origin /api + bearer JWT              | MQTT/TLS + device credentials + Ed25519 signature
      v                                           v
Nginx :3000 -> Spring Boot API <====== verified TLS ======> Mosquitto :8883
(curl / Postman may also call API :8080 directly)
  ├── AuthController / AuthService                 ├── Dynamic Security plugin
  ├── AuthenticationAuditService
  ├── JwtAuthenticationFilter                     ├── per-device literal publish ACL
  ├── DeviceController / DeviceService             └── backend-only subscribe role: iot/telemetry/+
  ├── MqttDynamicSecurityService
  ├── PolicyController / PolicyService
  ├── AccessController / ZeroTrustDecisionService
  ├── ProtectedResourceController / ProtectedResourceService
  ├── AccessAuditService / DeviceOwnershipAuditService / DeviceStatusAuditService
  ├── DeviceCredentialAuditService / PolicyChangeAuditService / TelemetryIngestionService
  ├── repositories / TelemetryQueryService
  └── request, audit, and telemetry DML
      | JDBC as DB_USERNAME (runtime role, DML only)
      v
PostgreSQL (Docker volume)
  ├── runtime role: owns no application schema objects
  ├── migration role: owns public objects; DDL restricted to migrations
  └── administrator: role bootstrap only

Compose startup dependency chain:
postgres healthy -> db-roles-init -> db-migrate (Flyway CLI) -> backend -> frontend (Nginx SPA)
```

The broker exposes no plaintext MQTT listener. Compose generates a local CA and a broker certificate with `mosquitto`, `localhost`, and `127.0.0.1` SAN entries. Backend MQTT clients trust that CA and enable hostname verification. These generated development certificates are ignored by Git. The separate migration container receives `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD`; those values are not present in the backend container environment. The database bootstrap also transfers ownership on existing volumes in place; it does not require deleting application data.

## Web console (Phase 19)

The frontend is a React/TypeScript single-page application with role-aware views for `ADMIN`, `SECURITY_ANALYST`, and `USER`. It consumes existing REST endpoints rather than introducing an aggregate dashboard API. Compose builds the static assets and serves them through Nginx on loopback port 3000; Nginx proxies same-origin `/api` and `/actuator` paths to Spring Boot. Local Vite development uses the same relative browser URLs with a server-side proxy to `VITE_PROXY_TARGET` (default `http://127.0.0.1:8080`). No browser CORS policy or browser-to-localhost request is required.

The bearer token stays in in-memory React state, so reload or sign-out requires a fresh login; it is not stored in Web Storage. One-time device credentials are shown in a temporary provisioning dialog and cleared when the dialog closes. UI role checks improve usability only; server authorization remains the security boundary.

## Authentication request path

```text
POST /api/auth/register
  -> validate request -> BCrypt hash -> UserRepository / PostgreSQL

POST /api/auth/login
  -> validate request -> LoginRateLimiter checks socket peer address
  -> over limit: HTTP 429 + Retry-After (before password verification)
  -> otherwise, DaoAuthenticationProvider verifies credentials
  -> failure: AuthenticationAuditService writes FAILURE, return generic 401
  -> success: JwtService signs token, then audit SUCCESS
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
  -> check role, device, status, policy (explicit DENY/default DENY)
  -> matching ALLOW + owner mismatch: audit DEVICE_NOT_OWNED, return 403
  -> matching ALLOW + owner match: query and return that device's samples
```

The API returns a policy decision for `/api/access/check`; it is not a reverse proxy or general enforcement layer for arbitrary IoT services. Access checks are attributed to the authenticated user. Policy subject and device state come from the registered device, not the request payload. API requester roles `USER` and `DEVICE` are evaluated; management roles get a recorded business DENY. The protected-resource path names the target device; the JWT supplies requester identity. For protected reads, an enabled USER must match `devices.owner_id`. ADMINs assign/transfer device ownership; non-owner DENYs are audited and do not query telemetry. The decision-only `/api/access/check` endpoint does not fetch protected data or apply this ownership check.

## Device ownership transfer audit

`PATCH /api/devices/{id}/owner` resolves the requested enabled USER and compares persisted owner IDs. A real change updates `devices.owner_id` and inserts an immutable `device_ownership_audits` snapshot in the same transaction; a failed transfer or same-owner no-op does not create an event. The snapshot records device code, previous/new owner IDs and usernames, authenticated ADMIN ID/username, and timestamp. `GET /api/devices/{id}/ownership-audits` returns paged transfer events to ADMIN and SECURITY_ANALYST roles (default and maximum page size 100). This management history is separate from `access_audits`, which records access decisions.

## Device-status and policy-change audit

The status endpoint and device revocation flow capture the current and requested `DeviceStatus`. When the value changes, `DeviceService` changes the entity and calls `DeviceStatusAuditService` in the same transaction. Its typed `device_status_audits` event snapshots the device ID/code, previous/new statuses, authenticated ADMIN ID/username, and time. A status no-op writes no event; an invalid or failed request rolls back without one. Broker/device-status enforcement and its separate `access_audits` decisions remain unchanged.

`PolicyService` records typed CREATE, UPDATE, and DELETE events via `PolicyChangeAuditService`. CREATE captures the after snapshot, UPDATE both before and after, and DELETE the last before snapshot. Mutations and audit insertion share one transaction; a no-op update and failed conflict/validation path do not add a successful-change event. The policy history table deliberately has no foreign key to `policies`, so `GET /api/policies/{id}/audits` can still return the DELETE history after the live row is removed. All audit-history APIs are available only to ADMIN and SECURITY_ANALYST. They use stable newest-first pagination (page size 1–100), inclusive time-range filters, and relevant exact-match event filters. Authentication attempts are stored separately from access decisions, ownership transfers, device-status transitions, and policy mutations.

## Device credential lifecycle audit (Phase 20)

Credential provisioning and rotation record `PROVISION`/`ROTATE` events in `device_credential_audits` within the same database transaction as the public-key update. Each immutable record stores the authenticated administrator, device snapshot, timestamp, and SHA-256 public-key fingerprints only. It deliberately excludes MQTT passwords, private signing keys, and complete public-key encodings. The history endpoint is restricted to ADMIN and SECURITY_ANALYST and uses the shared stable, filtered page envelope. Flyway V13 installs the append-only trigger; the runtime database role cannot disable it.

## MQTT identity, TLS, and telemetry path

```text
POST /api/devices or credential rotation
  -> generate a 256-bit random password and per-device Ed25519 key pair
  -> store only the public key; append a PROVISION/ROTATE audit with its SHA-256 fingerprint
  -> provision username=deviceCode, fixed mqttClientId, and a unique literal-topic role over verified TLS
  -> disclose mqttUsername, mqttPassword, and signing private key once (no-store)

publisher -> verified TLS + unique device credentials -> broker ACL
  -> only iot/telemetry/{same username} publish permitted
  -> Paho backend subscriber (separate restricted broker account)
  -> validate envelope, lock device row, verify signature over exact payload bytes
  -> invalid signature: INVALID_DEVICE_CREDENTIAL audit; stop before trusting sequence
  -> parse signed telemetry -> device status -> explicit policy DENY/default DENY
  -> reject sequence <= last accepted sequence, otherwise advance high-water mark
  -> audit decision; store telemetry only after ALLOW
```

Mosquitto Dynamic Security denies anonymous clients and keeps publishing/subscribing denied unless an ACL grants it. The backend creates a unique device role with one literal `publishClientSend iot/telemetry/{deviceCode}` ACL, so a device cannot publish to another device's topic. The backend subscriber has a separate role for `iot/telemetry/+`; it does not use the administrative broker identity. The admin account is used only for provisioning and bootstrap. Broker accounts stay enabled across device-status changes so valid, topic-scoped publishes reach the backend and an inactive-device decision can be audited; non-active data is never persisted. Device code and MQTT client ID are immutable after provisioning so the broker identity/ACL binding cannot silently drift.

The MQTT wire body is a JSON envelope with unpadded-base64url `payload` and `signature` fields. `payload` decodes to the telemetry JSON bytes containing positive per-device `sequence`, metric, value, unit, and optional `measuredAt`; Ed25519 signs those exact bytes. Each API-provisioned device receives a key pair, the database stores only its public key, and the private key is returned once with `Cache-Control: no-store`. Signature verification occurs before policy/replay evaluation and before trusting telemetry fields. Invalid signatures are audited as `DENY` / `INVALID_DEVICE_CREDENTIAL`, carry no untrusted sequence, and do not update the sequence high-water mark or persist telemetry. The row lock serializes signature-key rotation and sequence checks; `last_mqtt_sequence` is advanced in the same transaction as the access audit and telemetry insert, and a unique `(device_id, device_sequence)` constraint is a second replay/duplicate guard. A repeated or lower sequence receives `DENY` / `REPLAYED_MESSAGE`; policy DENY and inactive-device checks remain in force.

Mosquitto authenticates the MQTT username and assigns that device a role with a literal ACL for its registered topic. HTTP device ownership applies only to the protected telemetry read route; MQTT ingestion remains governed by broker identity/ACLs, application signature, status, policy, and replay checks. Older device records without a public key must have credentials rotated before they can send signed telemetry.

## Persistence

Flyway migrations `V1__create_users.sql` through `V13__audit_device_credential_lifecycle.sql` define the schema and demo rules. V4 adds access audits and telemetry; V5 temporarily added application credential hashes; V6 removes that redundant hash, adds the per-device sequence/high-water mark and replay audit reason, and assigns sequences to existing telemetry rows during upgrade; V7 adds `DEVICE_NOT_OWNED` to the allowed access-audit reasons; V8 creates the separate ownership-transfer history table; V9 adds separate status-transition and policy-change history tables with actor references and history indexes; V10 installs database triggers that reject UPDATE and DELETE statements on the four business audit-history tables; V11 adds a separate append-only table for validated API login attempts; V12 adds the nullable per-device MQTT signing public key so existing volumes can upgrade without losing data; V13 adds an append-only device credential lifecycle history with actor snapshots and public-key fingerprints, never the one-time password/private key. Device ownership itself remains in `devices.owner_id`. Hibernate uses `ddl-auto: validate`; it does not create tables automatically. Phase 14 configures a DML-only runtime role and a separate DDL-capable migration role. Phase 15 runs Flyway in a dedicated one-shot `db-migrate` container rather than Spring Boot; its migration credentials are not passed to the backend, and backend startup waits for migrations to complete. Phase 17 applies a bounded in-memory source-IP login limiter before password verification; Phase 18 verifies Ed25519 telemetry signatures before policy/replay evaluation. Compose's idempotent `db-roles-init` transfers `public` schema/table/sequence/view and audit-function ownership to the migration role before migration/backend startup, including on an existing volume; the runtime role receives only application DML and cannot disable/drop the append-only triggers.
