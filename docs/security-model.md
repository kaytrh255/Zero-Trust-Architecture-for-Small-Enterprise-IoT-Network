# Security model (current implementation)

## Authentication

`AuthService` hashes passwords with BCrypt before persistence. `users.password_hash` stores only the BCrypt hash. Registration and profile responses never expose the hash. `DaoAuthenticationProvider` uses the account repository for login; unknown usernames and incorrect passwords receive the same generic response.

Successful login creates a signed JWT with subject, issue time, and expiry. The key comes from `JWT_SECRET`; `JWT_EXPIRATION_SECONDS` controls token lifetime. `JwtAuthenticationFilter` verifies signature/expiry and reloads the current account, role, and enabled status for protected requests. The API is stateless.

## Role-based management

The bootstrap administrator comes from local environment variables. Bootstrap never promotes an existing regular account. Public registration always assigns `USER` and cannot accept a role.

- `ADMIN`: manage devices and policies; view both.
- `SECURITY_ANALYST`: view devices and policies.
- `USER` / `DEVICE`: no management access in these phases.

Method-level rules are in the controllers using `@PreAuthorize` and are enabled by `@EnableMethodSecurity`.

## Phase 4 policy selector

Policies store subject, resource, action, effect, enabled state, and description. The current `PolicyEvaluationService` considers only enabled exact matches and selects explicit `DENY` before `ALLOW`. Its empty result means there was no matching policy; the final access-decision service has not been added yet, so this phase does not claim that an IoT request is allowed or denied from this selector alone.

Device statuses `ACTIVE`, `INACTIVE`, `BLOCKED`, and `REVOKED` are stored and manageable. Device-state enforcement is also pending the access-decision phase.

## Transport, errors, and limits

CSRF is disabled because APIs use stateless bearer tokens in the `Authorization` header, not browser cookies. Docker Compose binds ports to loopback for local development. TLS is not implemented; local HTTP must not be described as HTTPS. Authentication/authorization failures return JSON without stack traces or database details.

Not implemented: the `AccessDecision` endpoint/service, end-to-end default-deny enforcement, audit logs, MQTT device credentials, token revocation, MFA, rate limiting, TLS, or production secret management.
