# Architecture (current implementation)

## Architectural style

The project is a modular monolith: one Spring Boot application owns REST APIs, authentication, device and policy management, access decisions, audits, and MQTT telemetry ingestion. PostgreSQL is the application database. Docker Compose runs PostgreSQL, a local Mosquitto broker, and the backend.

## Local deployment

```text
curl / Postman                         Simulated IoT publisher
      |                                           |
      | HTTP + bearer JWT                         | MQTT/TLS + device username/password/client ID
      v                                           v
Spring Boot application <====== verified TLS ======> Mosquitto :8883
  ├── AuthController / AuthService                 ├── Dynamic Security plugin
  ├── JwtAuthenticationFilter                     ├── per-device publish ACL: iot/telemetry/%u
  ├── DeviceController / DeviceService             └── backend-only subscribe role: iot/telemetry/+
  ├── MqttDynamicSecurityService
  ├── PolicyController / PolicyService
  ├── AccessController / ZeroTrustDecisionService
  ├── ProtectedResourceController / ProtectedResourceService
  ├── AccessAuditService / TelemetryIngestionService
  ├── repositories / Flyway migrations
  └── TelemetryQueryService
      |
      | JDBC as a non-superuser application role
      v
PostgreSQL (Docker volume)
```

The broker exposes no plaintext MQTT listener. Compose generates a local CA and a broker certificate with `mosquitto`, `localhost`, and `127.0.0.1` SAN entries. Backend MQTT clients trust that CA and enable hostname verification. These generated development certificates are ignored by Git.

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

The API returns a policy decision for the demo request; it is not a reverse proxy or general enforcement layer for arbitrary IoT services. Access checks are attributed to the authenticated user. Policy subject and device state come from the registered device, not the request payload. API requester roles `USER` and `DEVICE` are evaluated; management roles get a recorded business DENY. The protected-resource path names the target device; the JWT supplies requester identity. No device-ownership check is implemented.

## MQTT identity, TLS, and telemetry path

```text
POST /api/devices or credential rotation
  -> generate a 256-bit random password
  -> MqttDynamicSecurityService provisions username=deviceCode,
     fixed mqttClientId, and zt-device-publisher role over verified TLS
  -> disclose mqttUsername/mqttPassword once

publisher -> verified TLS + unique device credentials -> broker ACL
  -> only iot/telemetry/{same username} publish permitted
  -> Paho backend subscriber (separate restricted broker account)
  -> validate topic/payload and require positive sequence
  -> lock device row -> device status -> explicit policy DENY/default DENY
  -> reject sequence <= last accepted sequence, otherwise advance high-water mark
  -> audit decision; store telemetry only after ALLOW
```

Mosquitto Dynamic Security denies anonymous clients, keeps publishing/subscribing denied unless an ACL grants it, and restricts a device role to `publishClientSend iot/telemetry/%u`. The backend subscriber has a separate role for `iot/telemetry/+`; it does not use the administrative broker identity. The admin account is used only for provisioning and bootstrap. Broker accounts stay enabled across device-status changes so valid, topic-scoped publishes reach the backend and an inactive-device decision can be audited; non-active data is never persisted. Device code and MQTT client ID are immutable after provisioning so the broker identity/ACL binding cannot silently drift.

The telemetry body no longer contains an authentication secret. It contains a positive, monotonically increasing per-device `sequence`, metric, value, unit, and optional `measuredAt`. The row lock serializes concurrent checks, `last_mqtt_sequence` is advanced in the same transaction as the access audit and telemetry insert, and a unique `(device_id, device_sequence)` constraint is a second replay/duplicate guard. A repeated or lower sequence receives `DENY` / `REPLAYED_MESSAGE`; policy DENY and inactive-device checks remain in force.

The MQTT broker username is authenticated by Mosquitto, and its `%u` ACL binds the permitted topic to that username. This prototype does not add device ownership enforcement to the protected HTTP resource path and does not verify application-level message signatures.

## Persistence

Flyway migrations `V1__create_users.sql` through `V6__broker_mqtt_identity_tls_and_replay_protection.sql` define the schema and demo rules. V4 adds access audits and telemetry; V5 temporarily added application credential hashes; V6 removes that redundant hash, adds the per-device sequence/high-water mark and replay audit reason, and assigns sequences to existing telemetry rows during upgrade. Hibernate uses `ddl-auto: validate`; it does not create tables automatically.
