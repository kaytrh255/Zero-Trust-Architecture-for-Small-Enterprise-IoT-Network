# Security model (current implementation)

## Authentication

`AuthService` hashes passwords with BCrypt before persistence. `users.password_hash` stores only the BCrypt hash. Registration/profile responses never expose it. Login uses Spring Security's `DaoAuthenticationProvider`; unknown usernames and incorrect passwords receive the same generic response.

Successful login creates a signed JWT with subject, issue time, and expiry. The key comes from `JWT_SECRET`; `JWT_EXPIRATION_SECONDS` controls token lifetime. `JwtAuthenticationFilter` verifies signature/expiry and reloads the current account, role, and enabled status for protected requests. The API is stateless.

## Role-based management and access checks

The bootstrap administrator comes from local environment variables. Bootstrap never promotes an existing regular account. Public registration always assigns `USER` and cannot accept a role.

- `ADMIN`: manage devices/policies; read devices, policies, access audits, and telemetry.
- `SECURITY_ANALYST`: read devices, policies, access audits, and telemetry.
- `USER` / `DEVICE`: eligible requester roles for access-decision evaluation; they cannot manage policies/devices or read audit/telemetry history.

`POST /api/access/check` derives requester ID/name/role from the bearer-token principal. The caller cannot claim another account or submit trusted device type/status. Management roles receive a recorded business `DENY` (`REQUESTER_ROLE_NOT_ALLOWED`) rather than overriding resource policy. An evaluated DENY returns HTTP 200; missing/invalid authentication remains HTTP 401.

## Decision order and policy

`ZeroTrustDecisionService` loads the registered device by normalized device code while holding a pessimistic row lock. Device type is the policy subject; the client supplies only the device code, resource, and action.

1. Verify the requester role is eligible for the channel (`USER`/`DEVICE` for API; `DEVICE` internally for MQTT).
2. Require a registered device.
3. Require status `ACTIVE`; `INACTIVE`, `BLOCKED`, and `REVOKED` are denied.
4. Query enabled exact subject/resource/action policies. An explicit `DENY` is selected before `ALLOW`.
5. If no policy matches, return default `DENY`.
6. Persist the outcome/reason and context in `access_audits`.

No role, device status, or policy miss can be overridden by a matching ALLOW rule. The policy table does not support wildcards or contextual expressions.

## MQTT telemetry

The Compose Mosquitto service is loopback-bound, disallows anonymous connections, and uses a password file generated from `.env`. The backend's Paho subscriber validates the topic and a bounded JSON payload, then checks `device-telemetry` / `WRITE` through the same decision service. Only allowed samples are saved; blocked devices and missing policy default-deny.

The sample broker credential is shared by the backend and simulated publishers. Topic codes are looked up in the database but are not cryptographically bound to individual MQTT credentials. There is no per-device ACL, TLS, or message signing; this broker setup is for local demonstration only.

## Audit and limitations

Every valid API decision and MQTT telemetry decision is recorded, including denial reasons and matched policy snapshots. `GET /api/access/audits` and `GET /api/telemetry` are available only to `ADMIN` and `SECURITY_ANALYST`, with each endpoint capped at the latest 100 records. Authentication attempts, malformed requests rejected before evaluation, and policy-management changes are not access-audit events.

The access-check endpoint is a decision demonstration, not a transparent network gateway. The API request's device code is an unbound simulation context; the access service does not prove that the authenticated user owns or cryptographically controls that device. TLS, per-device credentials/ACLs, token revocation, MFA, rate limiting, production secrets management, and arbitrary resource enforcement are not implemented.
