# Security model (current implementation)

## Implemented in Phase 2

### Passwords

`AuthService` encodes passwords with Spring Security's `BCryptPasswordEncoder` before persistence. The `users.password_hash` column stores only the BCrypt hash. Registration and profile responses never expose the hash.

### Authentication

`POST /api/auth/login` delegates to an `AuthenticationManager` backed by `DaoAuthenticationProvider`. The provider loads the account from PostgreSQL and checks the submitted password against the BCrypt hash. Unknown usernames and incorrect passwords receive the same generic response.

### JWTs

A successful login creates a signed JWT with a subject, issue time, and expiry. The signing key is supplied through `JWT_SECRET`; it is not embedded in Java source. `JWT_EXPIRATION_SECONDS` controls token lifetime. The login response is marked `Cache-Control: no-store`. The API is stateless and does not create an HTTP login session.

`JwtAuthenticationFilter` verifies the signature and expiry for bearer tokens. It reloads the account from PostgreSQL on protected requests, so account disablement and role changes take effect without waiting for the token to expire. Missing, invalid, malformed, and expired tokens do not establish an authenticated principal.

### Registration and roles

Public registration accepts username, password, and full name only. The server assigns `USER` regardless of client input; this prevents self-assignment of `ADMIN`. The principal exposes the database role as a Spring Security authority (`ROLE_<role>`). Role-specific management rules will be added with the relevant later modules.

### Transport and errors

CSRF protection is disabled because this phase uses stateless bearer tokens in the `Authorization` header rather than browser cookies; no server-side session is created. Docker Compose binds published ports to loopback for local development. TLS is not implemented, and local HTTP must not be presented as HTTPS. Health details are limited to component statuses. Authentication and authorization failures return JSON without stack traces or database details.

## Not implemented yet

- Authorization by Zero Trust resource/action policy, device status, or request context.
- Access/audit logs, including invalid-login auditing.
- Token refresh, logout/revocation list, MFA, rate limiting, or brute-force protection.
- TLS for HTTP or MQTT, production secret management, and deployment hardening.

These limitations are intentional for the current student prototype stage; later documentation and demonstrations must not claim these controls exist before implementation.
