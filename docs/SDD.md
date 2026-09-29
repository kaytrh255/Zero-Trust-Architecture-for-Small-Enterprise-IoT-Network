# Software Design Description (SDD)

**Project:** Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Current implementation:** Phases 1–5. The system now returns and audits access decisions for its demo API and MQTT telemetry path; it is not a transparent production gateway.

## 1. Introduction

This document describes a locally runnable modular-monolith prototype for demonstrating selected Zero Trust concepts in a small enterprise IoT environment.

## 2. Problem Statement

An internal network location alone is not sufficient proof of identity or permission. The project authenticates API users, registers devices, stores access policies, and now evaluates demo resource requests using authenticated requester context, registered device state, action/resource, and policy.

## 3. Objectives

- Provide a reproducible local Spring Boot, PostgreSQL, Docker Compose, and MQTT foundation.
- Authenticate users with BCrypt-protected passwords and signed JWTs.
- Manage device identity/status and policy records with role-protected APIs.
- Apply explicit-deny and default-deny decisions to the prototype's access-check and telemetry paths.
- Persist access decisions and provide a small audit view.
- Keep the project understandable and demonstrable in a university defense.

## 4. Scope

Implemented: Compose/PostgreSQL, health endpoint, registration/login/JWT, user profile, device CRUD/status, policy CRUD, seeded rules, access decision API, access audit records, authenticated local Mosquitto, policy-gated MQTT telemetry ingestion, and read APIs for audit/telemetry. Planned: per-device MQTT credentials/ACLs, policy-change/authentication auditing, transparent resource-gateway enforcement, and React dashboard. Physical IoT hardware, Kubernetes, and SIEM are out of scope.

## 5. Functional Requirements

### Implemented
- Register users with BCrypt-hashed passwords; public registration assigns only `USER`.
- Log in and receive a time-limited JWT; require a bearer token for protected APIs.
- Bootstrap an administrator and local demo devices.
- Manage devices/status and policies with role restrictions.
- Check access using authenticated requester role, registered device type/status, resource, action, and enabled policies.
- Deny inactive/unknown devices, prefer explicit matching `DENY`, and default-deny unmatched requests.
- Persist decisions for valid API/MQTT checks and show the latest 100 audit records.
- Validate and policy-gate demo MQTT telemetry before storage; update device `last_seen_at` on accepted samples.

### Planned
- Bind every API/MQTT requester to a per-device credential and device-owned identity.
- Enforce decisions on arbitrary protected IoT data/control resources, not only this prototype's decision endpoint and telemetry ingestion.
- Audit authentication attempts and policy-management changes.
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
- `DeviceController` / `DeviceService`: device CRUD, status updates, and revocation.
- `PolicyController` / `PolicyService`: policy CRUD and input normalization.
- `PolicyEvaluationService`: deterministic exact-match policy selection.
- `AccessController` / `ZeroTrustDecisionService`: context construction, device/policy evaluation, and access outcomes.
- `AccessAuditService`: persists decisions and supplies the restricted recent-audit API.
- `MqttTelemetrySubscriber` / `TelemetryIngestionService`: broker subscription, validation, access evaluation, and accepted sample storage.
- `TelemetryQueryService`: restricted recent-telemetry API.
- JPA repositories/entities and Flyway migrations: persistence.
- `GlobalExceptionHandler`: consistent REST error responses.

## 10. Database Design

Migration `V1__create_users.sql` creates `users`: `id`, unique `username`, `password_hash`, `full_name`, `role`, `enabled`, `created_at`, and `updated_at`.

Migration `V2__create_devices.sql` creates `devices`: `id`, unique `device_code`, name, type, IP address, unique `mqtt_client_id`, status, owner foreign key, `created_at`, and nullable `last_seen_at`.

Migration `V3__create_policies.sql` creates `policies`: `id`, unique `name`, `subject`, `resource`, `action`, `effect`, `enabled`, description, and timestamps; it inserts the initial sensor/camera rules.

Migration `V4__add_access_audits_and_telemetry.sql` creates `access_audits` (requester/channel/device snapshots, resource/action, outcome/reason, matched-policy snapshot, timestamp) and `device_telemetry` (device, metric, numeric value, unit, measurement/receive times, MQTT topic). It adds sensor/camera telemetry-write rules. Hibernate validates the migrated schema; it does not create tables automatically.

## 11. Authentication Design

Passwords are BCrypt-encoded before saving. Login uses Spring Security's `DaoAuthenticationProvider` and issues a signed JWT with subject, issue time, and expiry. The secret and lifetime are environment-configured. Protected requests reload current role/enabled state from PostgreSQL.

## 12. Authorization Design

The HTTP filter chain is stateless. Registration, login, and health are public; other application routes require a valid token. Device/policy changes require `ADMIN`; reads permit `ADMIN` and `SECURITY_ANALYST`. Access checks are evaluated only for `USER`/`DEVICE` requester roles; an authenticated `ADMIN`/`SECURITY_ANALYST` receives a recorded business DENY rather than an HTTP authorization bypass. Audit and telemetry reads permit `ADMIN`/`SECURITY_ANALYST`.

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

The endpoint returns HTTP `200` for an evaluated `ALLOW` or `DENY`; authentication and malformed-input errors retain their HTTP error statuses. A decision is an auditable response, not a transparent proxy for arbitrary IoT traffic.

## 15. MQTT Architecture

Mosquitto listens on a host loopback port and requires a local username/password generated from `.env`. The backend connects using Eclipse Paho with automatic reconnect and subscribes to `iot/telemetry/+` at QoS 1. The topic contains a registered device code; the JSON body contains `metric`, numeric `value`, `unit`, and optional `measuredAt`.

The receiver bounds payload size, validates topic/fields, derives a `DEVICE`/`MQTT` context, and runs the same device-status/policy/default-deny checks against resource `device-telemetry` and action `WRITE`. Only an ALLOW sample is persisted. The starter has one shared broker credential and no per-device ACL/credential binding; a topic value alone is not cryptographic proof of device identity.

## 16. Audit Logging

Every valid API decision and valid MQTT telemetry decision is recorded, including DENY results and unknown/inactive devices. The audit row records requester or MQTT source, role/channel, device snapshot, resource/action, outcome/reason, matched policy snapshot, and time. `GET /api/access/audits` returns the latest 100 records to `ADMIN`/`SECURITY_ANALYST`.

Malformed API bodies are rejected by request validation before decision evaluation; malformed MQTT messages are logged and discarded before a policy decision. Authentication attempts and policy-management changes are not yet audited.

## 17. API Specification

Implemented routes are documented in [`api.md`](api.md): health, authentication, device/policy management, access decisions/audits, and telemetry reads. `/api/access/check` requires a bearer token; it is not an anonymous public endpoint.

## 18. Frontend Design

Not implemented. A React dashboard is planned for login, overview, devices, policies, access logs, and security events.

## 19. Security Requirements

Implemented: BCrypt, JWT signature/expiry validation, stateless authentication, input validation, role-protected management/audit APIs, environment-provided secrets, safe DTOs, active-device checks, explicit-deny precedence, default deny, bounded MQTT payloads, local broker authentication, and access-decision auditing. Not implemented: transparent enforcement on arbitrary resources, per-device MQTT credentials/ACLs, TLS, message signing, token revocation, MFA, rate limiting, or authentication/policy-change audit events.

## 20. Threat Scenarios

- **Password disclosure from database:** BCrypt stores a one-way hash, not plaintext.
- **Forged or expired API bearer token:** JWT signature/expiry checks reject it; current account status/role is reloaded.
- **Self-registration as administrator:** public registration assigns `USER` and does not accept a role.
- **Management role requests IoT access:** administrator/analyst roles receive a recorded `REQUESTER_ROLE_NOT_ALLOWED` deny.
- **Unknown, blocked, inactive, or revoked device:** the decision is DENY before policy selection.
- **Conflicting policy effects:** explicit matching DENY takes precedence over ALLOW.
- **No matching policy:** default DENY is returned and audited.
- **Oversized/invalid MQTT message:** the subscriber rejects it before persistence; valid messages still require an active registered device and an ALLOW policy.
- **Stolen shared MQTT credential / claimed topic device code:** local loopback and broker authentication reduce exposure, but per-device credentials, topic ACLs, TLS, and message signing are not implemented.

## 21. Test Plan

Unit tests cover JWT validation, BCrypt, device lifecycle, policy selection, access decision outcomes (ALLOW, explicit DENY, default DENY, inactive/unknown device, requester role), audit context, and MQTT telemetry validation/gating. Manual Phase 5 checks: call the access API for a seeded ALLOW, explicit DENY, blocked device, unknown resource/device; inspect audit events; publish an allowed sensor telemetry sample and confirm it appears in `GET /api/telemetry`; repeat with the blocked sensor and confirm no sample is stored. Maven/Docker runtime verification requires those tools.

## 22. Deployment

Local deployment uses `docker compose up --build -d`. Database, JWT, bootstrap-admin, and MQTT credentials come from an ignored `.env` file. PostgreSQL, backend, and Mosquitto host ports bind to loopback. The Mosquitto credential setup uses a named volume. This is a development deployment only.

## 23. Limitations

The access endpoint returns a decision but does not intercept arbitrary IoT resource routes. API device codes are simulated context, not device-bound credentials. MQTT currently uses one shared broker credential rather than per-device identity/ACLs. There is no TLS, frontend, policy-change/authentication audit, token refresh, or production secret-management system. A policy or access outcome should not be presented as enforcement over devices outside the demo telemetry path.

## 24. Future Development

Bind API and MQTT requests to individual device credentials; add topic ACLs/TLS and signed telemetry; audit authentication and policy changes; enforce decisions in real protected resource handlers; add the React dashboard, event search, retention controls, and end-to-end tests.
