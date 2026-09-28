# Software Design Description (SDD)

**Project:** Zero Trust Architecture for Small Enterprise IoT Network

**Vietnamese:** Triển khai kiến trúc Zero Trust cho mạng IoT doanh nghiệp nhỏ

**Current implementation:** Phase 1 foundation and Phase 2 authentication. Planned features are explicitly marked as not implemented.

## 1. Introduction

This document describes the design of a local, modular-monolith prototype for demonstrating selected Zero Trust concepts in a small enterprise IoT environment.

## 2. Problem Statement

An internal network location alone is not sufficient proof of identity or permission. The project will demonstrate authentication and, in later phases, evaluate device and resource access against explicit policy.

## 3. Objectives

- Provide a reproducible local Spring Boot and PostgreSQL foundation.
- Authenticate users with BCrypt-protected passwords and signed JWTs.
- Later demonstrate deterministic ALLOW/DENY decisions and audit records.
- Keep the design small enough to explain and test in a university defense.

## 4. Scope

Implemented now: Docker Compose, PostgreSQL connection, health endpoint, user registration, login, JWT verification, and a protected current-user endpoint. Planned: device and policy CRUD, access decisions, audit logs, MQTT simulation, and React dashboard. Production deployment, physical IoT hardware, Kubernetes, and SIEM are out of scope.

## 5. Functional Requirements

### Implemented
- Register with username, password, and full name; new public registrations receive `USER` role.
- Hash passwords with BCrypt.
- Log in and receive a time-limited signed JWT.
- Return current-user details only when the bearer token is valid.

### Planned
- Manage devices and their status.
- Evaluate resource/action requests using default-deny policies.
- Record all access decisions and show them on the dashboard.
- Process simulated MQTT telemetry.

## 6. Non-Functional Requirements

Prioritize understandable code, local reproducibility, input validation, least privilege for the database account, and testability. This prototype is not a production security gateway.

## 7. System Architecture

A single Spring Boot application serves REST APIs and uses PostgreSQL through Spring Data JPA. Docker Compose starts the application and database. Flyway owns schema migrations.

## 8. Technology Stack

Java 21, Spring Boot 3.5, Maven, Spring Web, Spring Security, Spring Data JPA, Hibernate, Flyway, PostgreSQL, JJWT, Docker, and Docker Compose. React and Mosquitto are planned for later phases.

## 9. System Components

- `AuthController`: registration, login, and current-user APIs.
- `AuthService`: registration rules, password hashing, login, and token response.
- `JpaUserDetailsService`: loads account identity and role from PostgreSQL.
- `JwtService` / `JwtAuthenticationFilter`: token signing and bearer-token validation.
- `SecurityConfig`: stateless HTTP security rules.
- `UserRepository` / `UserAccount`: user persistence.
- `GlobalExceptionHandler`: consistent REST error responses.
- Future modules: device management, policy evaluation, auditing, MQTT, and frontend.

## 10. Database Design

Migration `V1__create_users.sql` creates `users`: `id`, unique `username`, `password_hash`, `full_name`, `role`, `enabled`, `created_at`, and `updated_at`. Hibernate validates the migrated schema; it does not create it. Device, policy, and access-log tables are planned.

## 11. Authentication Design

Registration is public but cannot assign a role. Passwords are BCrypt-encoded before saving. Login authenticates through Spring Security's `DaoAuthenticationProvider` and issues a signed JWT with subject, issue time, and expiry. The token secret and expiry are environment-configured.

## 12. Authorization Design

Spring Security uses a stateless filter chain. Registration, login, and health are public; other routes require authentication. The account role is loaded as `ROLE_ADMIN`, `ROLE_SECURITY_ANALYST`, `ROLE_USER`, or `ROLE_DEVICE`. Role-specific resource permissions are not yet implemented.

## 13. Zero Trust Policy Model

Planned. Policies will identify a subject, resource, action, effect, and enabled state. Explicit DENY will take precedence over matching ALLOW; no matching ALLOW will result in default DENY. No policy table or policy engine exists yet.

## 14. Access Decision Flow

Planned. Future flow: authenticate requester, validate user/device status, identify resource/action/context, evaluate explicit DENY, evaluate matching ALLOW, otherwise default DENY, then write an audit record.

## 15. MQTT Architecture

Not implemented. Mosquitto and simulated devices will be added in a later phase. MQTT will be treated as transport; it will not itself be described as the Zero Trust policy layer.

## 16. Audit Logging

Not implemented. Authentication attempts and resource decisions are not currently written to an audit table. Later phases will add access logs and basic security-event summaries.

## 17. API Specification

Implemented endpoints are documented in [`api.md`](api.md): `GET /actuator/health`, `POST /api/auth/register`, `POST /api/auth/login`, and protected `GET /api/auth/me`. Device, policy, access-check, audit, and dashboard APIs are planned.

## 18. Frontend Design

Not implemented. A React dashboard is planned for login, overview, devices, policies, access logs, and security events.

## 19. Security Requirements

Implemented: BCrypt password hashing, JWT signature and expiry validation, stateless security, input validation, non-superuser database credentials, environment-provided JWT secret, generic authentication errors, and default protection for non-public routes. Not implemented: policy authorization, access auditing, TLS, token revocation, MFA, or brute-force defenses.

## 20. Threat Scenarios

- **Password disclosure from database:** BCrypt stores a one-way hash, not plaintext.
- **Forged or expired bearer token:** signature and expiry checks reject it.
- **Self-registration as administrator:** registration does not accept a role and assigns `USER`.
- **Disabled account reuses an existing token:** each protected request reloads enabled status from the database.
- **Stolen active token / brute-force attempts:** token lifetime limits exposure, but revocation lists, MFA, and rate limiting are not implemented.
- **Unauthorized IoT resource request:** not addressed until the policy engine is implemented.

## 21. Test Plan

Unit tests cover JWT subject/signature validation and BCrypt password matching. Manual Phase 2 integration test: register, log in, call `/api/auth/me` with the token, then repeat without a token and with invalid credentials to observe `401`. Runtime Docker verification must be run in an environment with Docker; it was unavailable in the coding sandbox.

## 22. Deployment

Local deployment uses `docker compose up --build -d`. Database and JWT settings come from an ignored `.env` file based on `.env.example`. The current Compose ports bind to loopback. This is a development deployment only.

## 23. Limitations

The current application authenticates users but does not yet decide IoT resource access. It has no device identity lifecycle, policy engine, access-log table, MQTT integration, frontend, HTTPS/TLS, refresh tokens, or production secret-management system.

## 24. Future Development

Continue with device management, then policy CRUD and the deterministic Zero Trust decision service, audit logging and security events, MQTT simulation, React dashboard, and end-to-end tests. Verify each phase before proceeding.
