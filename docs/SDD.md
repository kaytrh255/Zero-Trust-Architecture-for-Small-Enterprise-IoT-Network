# Software Design Description (SDD)

**Project:** Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Current implementation:** Phase 1 foundation, Phase 2 authentication, and Phase 3 device management. Planned features are explicitly marked as not implemented.

## 1. Introduction

This document describes a local, modular-monolith prototype for demonstrating selected Zero Trust concepts in a small enterprise IoT environment.

## 2. Problem Statement

An internal network location alone is not sufficient proof of identity or permission. The project implements user authentication and device administration first; later phases will evaluate IoT resource access against explicit policies and device state.

## 3. Objectives

- Provide a reproducible local Spring Boot and PostgreSQL foundation.
- Authenticate users with BCrypt-protected passwords and signed JWTs.
- Manage device identity and lifecycle with role-protected APIs.
- Later demonstrate deterministic ALLOW/DENY decisions and audit records.
- Keep the design small enough to explain and test in a university defense.

## 4. Scope

Implemented now: Docker Compose, PostgreSQL, health endpoint, registration/login/JWT, current-user endpoint, device CRUD, device status, a bootstrap administrator, and demo devices. Planned: policy evaluation, access logging, MQTT simulation, and React dashboard. Production deployment, physical IoT hardware, Kubernetes, and SIEM are out of scope.

## 5. Functional Requirements

### Implemented
- Register a user; public registration always assigns `USER`.
- Hash passwords with BCrypt; log in and receive a time-limited signed JWT.
- Return current-user details only when a valid bearer token is supplied.
- Bootstrap an administrator from local environment variables without promoting an existing regular user.
- Create, list, read, update, change status, and revoke devices with role checks.
- Retain deleted devices as `REVOKED` records.

### Planned
- Evaluate resource/action requests using device state and default-deny policies.
- Record access decisions and show security events on a dashboard.
- Process simulated MQTT telemetry.

## 6. Non-Functional Requirements

Prioritize understandable code, local reproducibility, input validation, least privilege for the database account, and testability. This prototype is not a production security gateway.

## 7. System Architecture

A single Spring Boot application serves REST APIs and uses PostgreSQL through Spring Data JPA. Docker Compose starts the application and database. Flyway owns schema migrations.

## 8. Technology Stack

Java 21, Spring Boot 3.5, Maven, Spring Web, Spring Security, Spring Data JPA, Hibernate, Flyway, PostgreSQL, JJWT, Docker, and Docker Compose. React and Mosquitto are planned for later phases.

## 9. System Components

- `AuthController` / `AuthService`: registration, login, and current-user APIs.
- `JwtService` / `JwtAuthenticationFilter`: token signing and bearer-token validation.
- `SecurityConfig`: stateless HTTP security and method-level authorization.
- `UserAccount` / `UserRepository`: account persistence and role.
- `DeviceController` / `DeviceService`: device CRUD, status updates, and revocation.
- `Device` / `DeviceRepository`: device persistence and owner link.
- `DemoDataInitializer`: local bootstrap administrator and example devices.
- `GlobalExceptionHandler`: consistent REST error responses.
- Future modules: policy evaluation, auditing, MQTT, and frontend.

## 10. Database Design

Migration `V1__create_users.sql` creates `users`: `id`, unique `username`, `password_hash`, `full_name`, `role`, `enabled`, `created_at`, and `updated_at`.

Migration `V2__create_devices.sql` creates `devices`: `id`, unique `device_code`, `device_name`, `device_type`, `ip_address`, unique `mqtt_client_id`, `status`, `owner_id` foreign key, `created_at`, and nullable `last_seen_at`. Hibernate validates the migrated schema; it does not create it. Policy and access-log tables are planned.

## 11. Authentication Design

Registration is public but cannot assign a role. Passwords are BCrypt-encoded before saving. Login authenticates through Spring Security's `DaoAuthenticationProvider` and issues a signed JWT with subject, issue time, and expiry. The token secret and expiry are environment-configured. Protected requests reload current account status and role from the database.

## 12. Authorization Design

Spring Security uses a stateless filter chain. Registration, login, and health are public; other application routes require authentication. Device API method rules are:

- `ADMIN`: list/read and all mutations.
- `SECURITY_ANALYST`: list/read only.
- `USER` and `DEVICE`: no device-management access.

Public registration assigns `USER`. A local bootstrap account receives `ADMIN` from environment configuration; an existing non-admin account is never promoted by bootstrap.

## 13. Zero Trust Policy Model

Planned. Policies will identify subject, resource, action, effect, and enabled state. Explicit DENY will take precedence over matching ALLOW; no matching ALLOW will result in default DENY. No policy table or policy engine exists yet.

## 14. Access Decision Flow

Planned. Future flow: authenticate requester, validate user/device status, identify resource/action/context, evaluate explicit DENY, evaluate matching ALLOW, otherwise default DENY, then write an audit record. Phase 3 persists device status but does not yet enforce it on IoT resource access.

## 15. MQTT Architecture

Not implemented. Mosquitto and simulated devices will be added later. MQTT will be treated as transport; it will not itself be described as the Zero Trust policy layer.

## 16. Audit Logging

Not implemented. Authentication attempts and device-management operations are not currently written to an audit table. Later phases will add access logs and basic security-event summaries.

## 17. API Specification

Implemented endpoints are documented in [`api.md`](api.md): health, authentication, and device-management APIs. Policy, access-check, audit, and dashboard APIs are planned.

## 18. Frontend Design

Not implemented. A React dashboard is planned for login, overview, devices, policies, access logs, and security events.

## 19. Security Requirements

Implemented: BCrypt password hashing, JWT signature and expiry validation, stateless security, input validation, role-protected device administration, non-superuser database credentials, environment-provided secrets, generic authentication errors, and safe DTOs. Not implemented: resource policy authorization, access auditing, TLS, token revocation, MFA, or brute-force defenses.

## 20. Threat Scenarios

- **Password disclosure from database:** BCrypt stores a one-way hash, not plaintext.
- **Forged or expired bearer token:** JWT signature and expiry checks reject it.
- **Self-registration as administrator:** public registration does not accept a role and assigns `USER`.
- **Disabled account reuses a token:** each protected request reloads enabled status from the database.
- **Regular user attempts device management:** method-level authorization returns `403`.
- **Blocked device attempts protected IoT access:** not yet enforced; the policy/access-decision phase must implement and test this.
- **Stolen active token / brute-force attempts:** JWT lifetime limits exposure, but revocation lists, MFA, and rate limiting are not implemented.

## 21. Test Plan

Unit tests cover JWT subject/signature validation, BCrypt matching, and device status transitions. Manual Phase 3 integration tests: authenticate as bootstrap admin, list seeded devices, create/update a device, change status, revoke with DELETE, then repeat a read as a `USER` token and observe `403`. Also test missing token (`401`), duplicate code/client ID (`409`), and invalid request (`400`). Runtime Docker verification must be run in an environment with Docker; the coding sandbox does not provide Docker, Java, or Maven.

## 22. Deployment

Local deployment uses `docker compose up --build -d`. Database, JWT, and bootstrap admin settings come from an ignored `.env` file based on `.env.example`. The current Compose ports bind to loopback. This is a development deployment only.

## 23. Limitations

The current application authenticates users and manages device records but does not yet decide IoT resource access. It has no policy engine, access-log table, MQTT integration, frontend, HTTPS/TLS, refresh tokens, or production secret-management system. A `BLOCKED` status is currently stored and shown; it is not yet an access decision.

## 24. Future Development

Continue with policy CRUD and the deterministic Zero Trust decision service, then audit logging/security events, MQTT simulation, React dashboard, and end-to-end tests. In particular, add and verify access-denial tests for blocked, revoked, unknown, and policy-disallowed devices before claiming those protections.
