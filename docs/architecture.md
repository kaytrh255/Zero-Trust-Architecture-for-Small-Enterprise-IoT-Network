# Architecture (current implementation)

## Architectural style

The project is a modular monolith: one Spring Boot application owns HTTP APIs, authentication, persistence, and (in later phases) policy evaluation and auditing. PostgreSQL is the only application database. Docker Compose runs the backend and database for local development.

## Current deployment

```text
Postman / curl
      |
      | HTTP on 127.0.0.1:8080
      v
Spring Boot application
  ├── Authentication API
  ├── Spring Security filter chain
  ├── JWT validation
  ├── AuthService
  ├── JPA repositories
  └── Flyway migrations
      |
      | JDBC as a non-superuser application role
      v
PostgreSQL (Docker volume)
```

## Phase 1 and 2 request paths

Registration:

```text
POST /api/auth/register
  -> Bean Validation
  -> normalize username
  -> BCrypt encode password
  -> UserRepository
  -> PostgreSQL users table
  -> return a safe user profile (no password hash)
```

Login:

```text
POST /api/auth/login
  -> AuthenticationManager
  -> DaoAuthenticationProvider
  -> JpaUserDetailsService / UserRepository
  -> BCrypt password comparison
  -> JwtService signs token
  -> return bearer token and user profile
```

Protected request:

```text
Authorization: Bearer <JWT>
  -> JwtAuthenticationFilter verifies signature and expiry
  -> reload current account and role from PostgreSQL
  -> reject disabled/missing account or invalid token
  -> SecurityFilterChain requires authentication
  -> controller handles request
```

The only protected application endpoint in Phase 2 is `GET /api/auth/me`. Role-specific administrative routes, the Zero Trust policy engine, audit module, MQTT, and React UI are future phases, not part of this current architecture.

## Persistence

Flyway runs `V1__create_users.sql` at startup. Hibernate uses `ddl-auto: validate`, so the migration—not automatic ORM schema generation—defines the database table. The backend uses a dedicated non-superuser PostgreSQL account created by the Postgres initialization script.
