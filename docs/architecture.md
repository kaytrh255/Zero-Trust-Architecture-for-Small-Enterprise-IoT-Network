# Architecture (current implementation)

## Architectural style

The project is a modular monolith: one Spring Boot application owns REST APIs, authentication, device and policy management, persistence, and (in later phases) final access decisions and auditing. PostgreSQL is the only application database. Docker Compose runs the backend and database for local development.

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
  ├── PolicyController / PolicyService / PolicyEvaluationService
  ├── repositories / Flyway migrations
  └── bootstrap administrator and demo devices
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

Protected HTTP requests pass through `JwtAuthenticationFilter`, which validates signature and expiry and reloads current user role/enabled status from PostgreSQL. `SecurityConfig` requires authentication for non-public routes; method-level rules protect device and policy operations.

## Policy management and selection

```text
POST/PUT/DELETE /api/policies (ADMIN)
  -> PolicyController role check
  -> PolicyService validation and normalization
  -> PolicyRepository / PostgreSQL policies table
```

`PolicyEvaluationService` queries enabled policies for an exact subject/resource/action match. Explicit `DENY` takes priority over `ALLOW`; no match returns an empty policy result. This is a policy selector, not yet the complete Zero Trust access-decision flow. Phase 5 will wrap the result in an `AccessDecision`, verify request identity and device status, apply default DENY, and expose the demonstration access-check endpoint.

## Persistence

Flyway migrations `V1__create_users.sql`, `V2__create_devices.sql`, and `V3__create_policies.sql` define the schema. The V3 migration seeds three demonstration policies. Hibernate uses `ddl-auto: validate`; it does not create tables automatically.
