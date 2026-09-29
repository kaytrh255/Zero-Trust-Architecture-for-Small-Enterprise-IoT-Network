# Security model (current implementation)

## Passwords and authentication

`AuthService` hashes passwords with Spring Security's `BCryptPasswordEncoder` before persistence. The `users.password_hash` column stores only the BCrypt hash. Registration and profile responses never expose the hash. Incorrect and unknown login credentials return the same generic response.

`POST /api/auth/login` delegates to an `AuthenticationManager` backed by `DaoAuthenticationProvider`. A successful login creates a signed JWT with subject, issue time, and expiry. The key comes from `JWT_SECRET`; `JWT_EXPIRATION_SECONDS` controls token lifetime. Login responses use `Cache-Control: no-store`.

`JwtAuthenticationFilter` verifies the JWT signature and expiry, then reloads the account from PostgreSQL on protected requests. Disabled or deleted accounts cannot continue using an otherwise valid token; current roles are loaded from the database rather than trusted from a client-supplied claim. The API is stateless and does not create a server-side login session.

## Role-based device management

The bootstrap administrator is created from `ADMIN_USERNAME`, `ADMIN_FULL_NAME`, and `ADMIN_PASSWORD`. The password is BCrypt-hashed. Bootstrap runs only when that username is absent; it never promotes an existing `USER` account. Public registration cannot submit a role and always assigns `USER`.

- `ADMIN`: list/read/create/update/change status/revoke devices.
- `SECURITY_ANALYST`: list/read devices.
- `USER` and `DEVICE`: no access to the device-management endpoints in this phase.

Method-level rules are in `DeviceController` using `@PreAuthorize`; they are enabled by `@EnableMethodSecurity`. Device owner is assigned from the authenticated administrator, not from request input. `DELETE` sets `REVOKED` to preserve the device identity for future audit history.

## Device status boundary

`ACTIVE`, `INACTIVE`, `BLOCKED`, and `REVOKED` are persisted and returned by the Device API. The access-check/policy engine does not exist yet, so this phase does **not** claim that a `BLOCKED` or `REVOKED` device is denied access to an IoT resource. That enforcement is scheduled for the Zero Trust decision phase.

## Transport, errors, and limits

CSRF is disabled because the API uses stateless bearer tokens in the `Authorization` header rather than browser cookies. Docker Compose binds published ports to loopback for local development. TLS is not implemented; local HTTP must not be described as HTTPS. Health details are limited to component statuses. Authentication and authorization failures return JSON without stack traces or database details.

Not implemented: Zero Trust resource/action policies, access audit logs, device credentials, JWT refresh/logout/revocation list, MFA, rate limiting, brute-force protection, TLS, or production secret management.
