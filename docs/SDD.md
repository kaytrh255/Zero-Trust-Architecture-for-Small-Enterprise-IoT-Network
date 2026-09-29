# Software Design Description (SDD)

**Project:** Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Current implementation:** Phases 1–6. The system audits access decisions, authenticates MQTT publishers with per-device application tokens, and enforces policy before returning its protected demo telemetry resource; it is not a transparent production gateway.

## 1. Introduction

This document describes a locally runnable modular-monolith prototype for demonstrating selected Zero Trust concepts in a small enterprise IoT environment.

## 2. Problem Statement

An internal network location alone is not sufficient proof of identity or permission. The project authenticates API users, registers devices with one-time per-device MQTT tokens, stores access policies, and evaluates demo resource requests using authenticated requester context, registered device state, action/resource, and policy.

## 3. Objectives

- Provide a reproducible local Spring Boot, PostgreSQL, Docker Compose, and MQTT foundation.
- Authenticate users with BCrypt-protected passwords and signed JWTs.
- Manage device identity/status and policy records with role-protected APIs.
- Apply explicit-deny and default-deny decisions to the prototype's access-check and telemetry paths.
- Persist access decisions and provide a small audit view.
- Keep the project understandable and demonstrable in a university defense.

## 4. Scope

Implemented: Compose/PostgreSQL, health endpoint, registration/login/JWT, user profile, device CRUD/status and one-time credential provisioning/rotation, policy CRUD, seeded rules, access decision API, access audit records, authenticated local Mosquitto, per-device application-token validation for telemetry, policy-gated MQTT ingestion, protected telemetry-resource reads, and audit/telemetry read APIs. Planned: broker-side per-device MQTT credentials/ACLs, policy-change/API-authentication auditing, TLS/message signing, and React dashboard. Physical IoT hardware, Kubernetes, and SIEM are out of scope.

## 5. Functional Requirements

### Implemented
- Register users with BCrypt-hashed passwords; public registration assigns only `USER`.
- Log in and receive a time-limited JWT; require a bearer token for protected APIs.
- Bootstrap an administrator and local demo devices.
- Manage devices/status and policies with role restrictions.
- Check access using authenticated requester role, registered device type/status, resource, action, and enabled policies.
- Deny inactive/unknown devices, prefer explicit matching `DENY`, and default-deny unmatched requests.
- Persist decisions for valid API/MQTT checks and show the latest 100 audit records.
- Issue random per-device MQTT application tokens once during device creation/rotation; persist only BCrypt hashes and reject/audit invalid credentials.
- Validate and policy-gate demo MQTT telemetry before storage; update device `last_seen_at` on accepted samples.
- Enforce `sensor-data` / `READ` before querying telemetry at the protected demo resource endpoint; return HTTP 403 with no data on DENY.

### Planned
- Add broker-side per-device logins and topic ACLs; use TLS and signed/replay-resistant messages outside the local demo.
- Extend enforcement beyond the protected telemetry route to any future resource handlers.
- Audit API authentication attempts and policy-management changes.
- Add a web dashboard.

## 6. Non-Functional Requirements

Prioritize understandable code, local reproducibility, input validation, least privilege for the database account, bounded telemetry payloads, and testability. This prototype is not a production security gateway.

## 7. System Architecture

A single Spring Boot application serves authentication, device, policy, access-decision, audit, and telemetry APIs. It uses PostgreSQL through Spring Data JPA/Flyway. A loopback-bound Mosquitto broker receives demo telemetry; the backend uses Eclipse Paho to subscribe and routes accepted samples into PostgreSQL. Docker Compose runs the services locally.

## 8. Technology Stack

Java 21, Spring Boot 3.5, Maven, Spring Web, Spring Security, Spring Data JPA, Hibernate, Flyway, PostgreSQL, JJWT, Eclipse Paho MQTT v3, Eclipse Mosquitto, Docker, and Docker Compose. React is planned for a later phase.

## 9. System Components

- `AuthController` / `AuthService`: registration, login, and current-user APIs.
- `JwtService` / `JwtAuthenticationFilter`: token signing and bearer-token validation.
- `SecurityConfig`: stateless HTTP security and method-level authorization.
- `DeviceController` / `DeviceService`: device CRUD, status updates, revocation, and one-time credential provisioning/rotation.
- `DeviceCredentialService`: issue random per-device tokens, store BCrypt hashes, and authenticate telemetry publishers against the topic device code.
- `PolicyController` / `PolicyService`: policy CRUD and input normalization.
- `PolicyEvaluationService`: deterministic exact-match policy selection.
- `AccessController` / `ZeroTrustDecisionService`: context construction, device/policy evaluation, and access outcomes.
- `AccessAuditService`: persists decisions and supplies the restricted recent-audit API.
- `ProtectedResourceController` / `ProtectedResourceService`: enforce the access decision before querying the demo telemetry resource.
- `MqttTelemetrySubscriber` / `TelemetryIngestionService`: broker subscription, validation, access evaluation, and accepted sample storage.
- `TelemetryQueryService`: restricted recent-telemetry API.
- JPA repositories/entities and Flyway migrations: persistence.
- `GlobalExceptionHandler`: consistent REST error responses.

## 10. Database Design

Migration `V1__create_users.sql` creates `users`: `id`, unique `username`, `password_hash`, `full_name`, `role`, `enabled`, `created_at`, and `updated_at`.

Migration `V2__create_devices.sql` creates `devices`: `id`, unique `device_code`, name, type, IP address, unique `mqtt_client_id`, status, owner foreign key, `created_at`, and nullable `last_seen_at`.

Migration `V3__create_policies.sql` creates `policies`: `id`, unique `name`, `subject`, `resource`, `action`, `effect`, `enabled`, description, and timestamps; it inserts the initial sensor/camera rules.

Migration `V4__add_access_audits_and_telemetry.sql` creates `access_audits` (requester/channel/device snapshots, resource/action, outcome/reason, matched-policy snapshot, timestamp) and `device_telemetry` (device, metric, numeric value, unit, measurement/receive times, MQTT topic). It adds sensor/camera telemetry-write rules. Migration `V5__bind_device_credentials.sql` adds a BCrypt-hash field for device tokens and the invalid-credential audit reason. Existing devices retain a null credential hash until an administrator rotates the credential and securely records the one-time token; until then MQTT ingestion fails closed for those devices. Hibernate validates the migrated schema; it does not create tables automatically.

## 11. Authentication Design

Passwords are BCrypt-encoded before saving. Login uses Spring Security's `DaoAuthenticationProvider` and issues a signed JWT with subject, issue time, and expiry. The secret and lifetime are environment-configured. Protected requests reload current role/enabled state from PostgreSQL.

## 12. Authorization Design

The HTTP filter chain is stateless. Registration, login, and health are public; other application routes require a valid token. Device/policy changes require `ADMIN`; reads permit `ADMIN` and `SECURITY_ANALYST`. Access checks are evaluated only for `USER`/`DEVICE` requester roles; an authenticated `ADMIN`/`SECURITY_ANALYST` receives a recorded business DENY rather than an HTTP authorization bypass. Audit and telemetry reads permit `ADMIN`/`SECURITY_ANALYST`. The protected telemetry resource requires a valid JWT and makes a `USER`/`DEVICE` access decision; data is queried only after ALLOW.

Registration always assigns `USER`. A local bootstrap account gets `ADMIN` from environment configuration; bootstrap never promotes an existing user. No API accepts requester ID, role, device type, or device status as trusted request fields.

## 13. Zero Trust Policy Model

Policy records contain `subject`, `resource`, `action`, `effect`, and `enabled`. The selector uses the registered device type as policy subject, normalizes subject/resource, and matches exact resource/action. Only enabled policies are considered. A matching explicit `DENY` is selected before `ALLOW`; wildcards and context-aware policy expressions are not implemented.

## 14. Access Decision Flow

For `POST /api/access/check`, the caller must present a valid JWT. The controller derives requester ID/name/role from the principal; the body contains only device code, resource, and action. `ZeroTrustDecisionService` normalizes identifiers, loads the device under a pessimistic row lock, and then applies:

1. API requester role must be `USER` or `DEVICE` (MQTT internal requester role is `DEVICE`).
2. Device code must identify a registered device.
3. Device status must be `ACTIVE`; inactive, blocked, and revoked devices are denied before policy evaluation.
4. Evaluate enabled exact subject/resource/action policies; explicit `DENY` wins over `ALLOW`.
5. If no policy matches, return default `DENY`.
6. Persist the outcome/reason and request snapshots in `access_audits` before returning.

The endpoint returns HTTP `200` for an evaluated `ALLOW` or `DENY`; authentication and malformed-input errors retain their HTTP error statuses. Separately, `GET /api/resources/devices/{deviceCode}/telemetry` evaluates a fixed `sensor-data` / `READ` operation and reads the database only on ALLOW; DENY returns HTTP `403`, its audit decision, and no resource data. This sample enforcement does not intercept arbitrary IoT traffic.

## 15. MQTT Architecture

Mosquitto listens on a host loopback port and requires a local username/password generated from `.env`. The backend connects using Eclipse Paho with automatic reconnect and subscribes to `iot/telemetry/+` at QoS 1. The topic contains a registered device code; the JSON body contains a one-time per-device `deviceToken`, `metric`, numeric `value`, `unit`, and optional `measuredAt`. Device tokens are random and only BCrypt hashes are persisted; creation and rotation disclose the plaintext once.

The receiver bounds payload size, validates topic/fields, checks that the per-device token hash matches the registered device named by the topic, then derives a `DEVICE`/`MQTT` context and runs the same device-status/policy/default-deny checks against resource `device-telemetry` and action `WRITE`. Invalid device credentials are recorded as DENY without attributing the attempt to the claimed device. Only an ALLOW sample is persisted. The application validates per-device bearer tokens, but the broker itself still uses one shared local account and has no per-device topic ACLs; MQTT is non-TLS, so this remains a local demonstration, not a production device-authentication design.

## 16. Audit Logging

Every valid API decision, valid MQTT policy decision, and syntactically valid telemetry attempt with an invalid device token is recorded, including DENY outcomes. Invalid-credential audits use requester `mqtt:unauthenticated` and do not trust device attributes from the topic. The audit row records requester or MQTT source, role/channel, device snapshot, resource/action, outcome/reason, matched policy snapshot, and time. `GET /api/access/audits` returns the latest 100 records to `ADMIN`/`SECURITY_ANALYST`.

Malformed API bodies are rejected by request validation before decision evaluation; malformed MQTT messages are logged and discarded before a policy decision. Authentication attempts and policy-management changes are not yet audited.

## 17. API Specification

Implemented routes are documented in [`api.md`](api.md): health, authentication, device/policy/credential management, access decisions/audits, telemetry reads, and the protected telemetry resource. `/api/access/check` requires a bearer token; it is not an anonymous public endpoint.

## 18. Frontend Design

Not implemented. A React dashboard is planned for login, overview, devices, policies, access logs, and security events.

## 19. Security Requirements

Implemented: BCrypt, JWT signature/expiry validation, stateless authentication, input validation, role-protected management/audit APIs, environment-provided secrets, safe DTOs, active-device checks, explicit-deny precedence, default deny, bounded MQTT payloads, local broker authentication, one-time per-device application tokens, protected telemetry-resource enforcement, and access-decision auditing. Not implemented: transparent enforcement on arbitrary resources, broker-side per-device logins/ACLs, TLS, signed/replay-resistant telemetry, device-token expiry, MFA, rate limiting, or API-authentication/policy-change audit events.

## 20. Threat Scenarios

- **Password disclosure from database:** BCrypt stores a one-way hash, not plaintext.
- **Forged or expired API bearer token:** JWT signature/expiry checks reject it; current account status/role is reloaded.
- **Self-registration as administrator:** public registration assigns `USER` and does not accept a role.
- **Management role requests IoT access:** administrator/analyst roles receive a recorded `REQUESTER_ROLE_NOT_ALLOWED` deny.
- **Unknown, blocked, inactive, or revoked device:** the decision is DENY before policy selection.
- **Conflicting policy effects:** explicit matching DENY takes precedence over ALLOW.
- **No matching policy:** default DENY is returned and audited.
- **Oversized/malformed MQTT message:** the subscriber rejects it before persistence; malformed payloads do not create access-decision audit rows.
- **Missing/wrong per-device token or token used on another device topic:** ingestion rejects and audits it without storing telemetry; the access decision uses the registered device record only after token verification.
- **Stolen shared broker credential:** it permits local broker connection but is insufficient to persist telemetry without that device's token. The bearer token itself is sent without TLS and can be replayed until rotated; broker-side topic ACLs/signatures are not implemented.

## 21. Test Plan

Unit tests cover JWT validation, BCrypt, device lifecycle, policy selection, access decision outcomes (ALLOW, explicit DENY, default DENY, inactive/unknown device, requester role), device-token binding, audit context, protected-resource enforcement (never query after DENY), and MQTT credential/policy gating. Manual Phase 6 checks: rotate a seeded device token; verify a token on its own topic is accepted but the same token on another topic or a wrong token is audited and not stored; verify token rotation invalidates the old token; call the protected resource as a USER for an allowed sensor, blocked sensor, and camera without a matching policy, expecting 200 only for ALLOW and 403/no data for DENY; inspect audit rows. Maven/Docker runtime verification requires those tools.

## 22. Deployment

Local deployment uses `docker compose up --build -d`. Database, JWT, bootstrap-admin, and MQTT credentials come from an ignored `.env` file. PostgreSQL, backend, and Mosquitto host ports bind to loopback. The Mosquitto credential setup uses a named volume. This is a development deployment only.

## 23. Limitations

The `/api/access/check` endpoint returns a decision; the separate protected telemetry route enforces only that demo resource. MQTT application tokens are per-device, but the broker still uses one shared account and does not enforce per-device topic ACLs. Tokens are bearer secrets in non-TLS local MQTT payloads and have no expiry or replay protection. There is no frontend, policy-change/API-authentication audit, or production secret-management system. A policy or access outcome should not be presented as enforcement over devices outside the protected demo telemetry path.

## 24. Future Development

Add broker-side per-device MQTT credentials/ACLs, TLS and signed/replay-resistant telemetry; audit API authentication and policy changes; extend enforcement to additional protected resources; add the React dashboard, event search, retention controls, and end-to-end tests.
