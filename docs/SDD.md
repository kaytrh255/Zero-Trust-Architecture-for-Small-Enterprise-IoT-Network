# Software Design Description (SDD)

**Project:** Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Current implementation:** Phases 1–4. End-to-end Zero Trust access decisions and audit logging are not yet implemented.

## 1. Introduction

This document describes a local modular-monolith prototype for demonstrating selected Zero Trust concepts in a small enterprise IoT environment.

## 2. Problem Statement

An internal network location alone is not sufficient proof of identity or permission. The project builds user authentication, device management, and policy storage first; a later phase will evaluate each IoT resource request using identity, device state, action, and policy.

## 3. Objectives

- Provide a reproducible local Spring Boot and PostgreSQL foundation.
- Authenticate users with BCrypt-protected passwords and signed JWTs.
- Manage device identity/status and policy records with role-protected APIs.
- Add deterministic default-deny access decisions and auditing in later phases.
- Keep the project understandable and demonstrable in a university defense.

## 4. Scope

Implemented: Compose/PostgreSQL, health endpoint, registration/login/JWT, user profile endpoint, device CRUD/status, policy CRUD, seeded example policies, and a policy-selection component. Planned: final access decisions, audit logs/security events, MQTT simulation, and React dashboard. Production deployment, physical IoT hardware, Kubernetes, and SIEM are out of scope.

## 5. Functional Requirements

### Implemented
- Register users with BCrypt-hashed passwords; public registration assigns only `USER`.
- Log in and receive a time-limited JWT.
- Require a valid bearer token for protected routes.
- Bootstrap an administrator and local demo devices.
- Manage device records/status with role restrictions.
- Create, read, update, and delete policies with role restrictions.
- Select an enabled exact-match policy, preferring explicit `DENY`.

### Planned
- Evaluate every protected IoT access request.
- Return ALLOW/DENY using authentication, device status, roles, policy, resource, action, and default-deny logic.
- Record each access attempt and display events.
- Process simulated MQTT data.

## 6. Non-Functional Requirements

Prioritize understandable code, local reproducibility, input validation, least privilege for the database account, and testability. This prototype is not a production security gateway.

## 7. System Architecture

A single Spring Boot application serves authentication, device, and policy REST APIs. It uses PostgreSQL through Spring Data JPA. Docker Compose runs the application and database. Flyway owns schema migrations.

## 8. Technology Stack

Java 21, Spring Boot 3.5, Maven, Spring Web, Spring Security, Spring Data JPA, Hibernate, Flyway, PostgreSQL, JJWT, Docker, and Docker Compose. React and Mosquitto are planned for later phases.

## 9. System Components

- `AuthController` / `AuthService`: registration, login, and current-user APIs.
- `JwtService` / `JwtAuthenticationFilter`: token signing and bearer-token validation.
- `SecurityConfig`: stateless HTTP security and method-level authorization.
- `DeviceController` / `DeviceService`: device CRUD, status updates, and revocation.
- `PolicyController` / `PolicyService`: policy CRUD and input normalization.
- `PolicyEvaluationService`: deterministic exact-match policy selection.
- JPA repositories and entity classes: database access and persistence.
- `GlobalExceptionHandler`: consistent REST error responses.
- Future components: `ZeroTrustDecisionService`, access auditing, MQTT integration, and frontend.

## 10. Database Design

Migration `V1__create_users.sql` creates `users`: `id`, unique `username`, `password_hash`, `full_name`, `role`, `enabled`, `created_at`, and `updated_at`.

Migration `V2__create_devices.sql` creates `devices`: `id`, unique `device_code`, name, type, IP address, unique `mqtt_client_id`, status, owner foreign key, `created_at`, and nullable `last_seen_at`.

Migration `V3__create_policies.sql` creates `policies`: `id`, unique `name`, `subject`, `resource`, `action`, `effect`, `enabled`, description, and timestamps. It inserts the initial sensor/camera policy examples. Hibernate validates the migrated schema; it does not create tables automatically.

## 11. Authentication Design

Passwords are BCrypt-encoded before saving. Login uses Spring Security's `DaoAuthenticationProvider` and issues a signed JWT with subject, issue time, and expiry. The secret and lifetime are environment-configured. Protected requests reload current role/enabled state from the database.

## 12. Authorization Design

The HTTP filter chain is stateless. Registration, login, and health are public; other application routes require a valid token. Device and policy APIs use method security:

- `ADMIN`: read and mutate devices/policies.
- `SECURITY_ANALYST`: read devices/policies.
- `USER` and `DEVICE`: no management access.

Registration always assigns `USER`. A local bootstrap account gets `ADMIN` from environment configuration; bootstrap never promotes an existing user.

## 13. Zero Trust Policy Model

Implemented policy records contain `subject`, `resource`, `action`, `effect`, and `enabled`. The current selector matches normalized subject/resource and exact action, considers enabled policies only, and selects explicit DENY before ALLOW. Wildcards and context-aware matching are not implemented. Final decision behavior and default DENY belong to Phase 5.

## 14. Access Decision Flow

Not implemented yet. Planned flow: authenticate requester, validate user/device status, identify resource/action/context, evaluate explicit DENY, evaluate matching ALLOW, otherwise default DENY, then write an audit record. Phase 4's selector is not itself the final decision or an access-control boundary for IoT resources.

## 15. MQTT Architecture

Not implemented. Mosquitto and simulated devices will be added later. MQTT will be treated as transport; it will not itself be described as the Zero Trust policy layer.

## 16. Audit Logging

Not implemented. Policy changes, authentication attempts, and protected access attempts are not written to an audit table yet. Later phases will add access logs and basic security-event summaries.

## 17. API Specification

Implemented endpoints are documented in [`api.md`](api.md): health, authentication, device management, and policy management. `/api/access/check`, audit, dashboard, and MQTT APIs are planned.

## 18. Frontend Design

Not implemented. A React dashboard is planned for login, overview, devices, policies, access logs, and security events.

## 19. Security Requirements

Implemented: BCrypt, JWT signature/expiry validation, stateless authentication, input validation, role-protected device/policy management, non-superuser database credentials, environment-provided secrets, safe DTOs, and explicit-deny precedence in policy selection. Not implemented: final resource policy enforcement, default-deny access endpoint, access auditing, TLS, token revocation, MFA, or brute-force defenses.

## 20. Threat Scenarios

- **Password disclosure from database:** BCrypt stores a one-way hash, not plaintext.
- **Forged or expired bearer token:** JWT signature and expiry checks reject it.
- **Self-registration as administrator:** public registration assigns `USER` and does not accept a role.
- **Regular user attempts policy/device management:** method-level authorization returns `403`.
- **Conflicting policies:** matching explicit DENY is selected before ALLOW.
- **No matching policy / blocked device:** final request denial is not yet implemented; Phase 5 must prove default DENY and device-status rejection.
- **Stolen active token / brute-force attempts:** token lifetime limits exposure, but revocation lists, MFA, and rate limiting are not implemented.

## 21. Test Plan

Unit tests cover JWT validation, BCrypt matching, device status transitions, and policy selection (DENY precedence, ALLOW match, no match). Manual Phase 4 integration tests: authenticate as admin, list seeded policies, create/update/delete a policy, verify duplicate names return `409`, and try policy reads/writes as a normal `USER` to confirm `403`. Runtime Docker/Maven verification must be run in an environment with those tools; they are unavailable in the coding sandbox.

## 22. Deployment

Local deployment uses `docker compose up --build -d`. Database, JWT, and bootstrap-admin settings come from an ignored `.env` file. The current Compose ports bind to loopback. This is a development deployment only.

## 23. Limitations

The current system manages identities, device metadata, and policy records but does not yet decide IoT resource access. It has no `/api/access/check`, access log table, MQTT integration, frontend, HTTPS/TLS, token refresh, or production secret-management system. A policy row does not currently grant or block IoT traffic.

## 24. Future Development

Implement `AccessContext`, `AccessDecision`, and `ZeroTrustDecisionService`; combine identity, device status, role, resource, action, policy, and request context; enforce explicit DENY and default DENY on the access endpoint; then add audit logging/security events, MQTT simulation, React dashboard, and end-to-end tests.
