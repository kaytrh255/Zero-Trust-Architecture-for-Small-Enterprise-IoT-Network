# Security model (current implementation)

## Authentication

`AuthService` hashes passwords with BCrypt before persistence. `users.password_hash` stores only the BCrypt hash. Registration/profile responses never expose it. Login uses Spring Security's `DaoAuthenticationProvider`; unknown usernames and incorrect passwords receive the same generic response.

Successful login creates a signed JWT with subject, issue time, and expiry. The key comes from `JWT_SECRET`; `JWT_EXPIRATION_SECONDS` controls token lifetime. `JwtAuthenticationFilter` verifies signature/expiry and reloads the current account, role, and enabled status for protected requests. The API is stateless.

## Role-based management and access checks

The bootstrap administrator comes from local environment variables. Bootstrap never promotes an existing regular account. Public registration always assigns `USER` and cannot accept a role.

- `ADMIN`: manage devices/policies and device credential rotation; read devices, policies, access audits, and telemetry.
- `SECURITY_ANALYST`: read devices, policies, access audits, and telemetry.
- `USER` / `DEVICE`: eligible requester roles for access-decision evaluation and policy-protected resource reads; they cannot manage policies/devices or read the unrestricted audit/telemetry history endpoints.

`POST /api/access/check` and the protected telemetry route derive requester ID/name/role from the bearer-token principal. The caller cannot claim another account or submit trusted device type/status. Management roles receive a recorded business `DENY` (`REQUESTER_ROLE_NOT_ALLOWED`) rather than overriding resource policy. The decision endpoint returns HTTP 200 for an evaluated DENY; the protected telemetry route returns HTTP 403 and no resource data on DENY. Missing/invalid authentication remains HTTP 401.

## Decision order and policy

`ZeroTrustDecisionService` loads the registered device by normalized device code while holding a pessimistic row lock. Device type is the policy subject; the client supplies only the device code, resource, and action.

1. Verify the requester role is eligible for the channel (`USER`/`DEVICE` for API; `DEVICE` internally for MQTT).
2. Require a registered device.
3. Require status `ACTIVE`; `INACTIVE`, `BLOCKED`, and `REVOKED` are denied.
4. Query enabled exact subject/resource/action policies. An explicit `DENY` is selected before `ALLOW`.
5. If no policy matches, return default `DENY`.
6. Persist the outcome/reason and context in `access_audits`.

No role, device status, or policy miss can be overridden by a matching ALLOW rule. `ProtectedResourceService` queries recent telemetry only after the decision service returns ALLOW; on DENY, it returns an empty telemetry array without running a telemetry query. The policy table does not support wildcards or contextual expressions.

## MQTT telemetry

The Compose Mosquitto service is loopback-bound, disallows anonymous connections, and uses a password file generated from `.env`. Device creation and credential rotation issue a random 256-bit per-device bearer token; only its BCrypt hash is stored, and plaintext is disclosed once. The Paho subscriber validates the topic/payload, verifies the token against the registered device named by the topic, and then checks `device-telemetry` / `WRITE` through the same decision service. A token cannot be used on a different registered device topic; invalid tokens are audited as `INVALID_DEVICE_CREDENTIAL`. Only allowed samples are saved; blocked devices and missing policy default-deny.

The broker login remains shared between the backend and local publishers and Mosquitto has no per-device topic ACLs. The per-device token is an application-layer credential in the JSON message, not broker-layer authentication. There is no TLS, token expiry, replay protection, or message signing; do not use this broker setup over an untrusted network.

## Audit and limitations

Every evaluated API decision, MQTT policy decision, and syntactically valid telemetry attempt with an invalid device token is recorded, including denial reasons and matched policy snapshots. `GET /api/access/audits` and `GET /api/telemetry` are available only to `ADMIN` and `SECURITY_ANALYST`, with each endpoint capped at the latest 100 records. API authentication attempts, malformed requests rejected before credential/policy evaluation, and policy-management changes are not access-audit events.

The access-check endpoint remains a decision demonstration; `GET /api/resources/devices/{deviceCode}/telemetry` enforces this demo resource only, not arbitrary network traffic. In HTTP, the device code names the target resource while requester identity comes from the JWT; no per-user device-ownership rule is implemented. MQTT tokens are bearer secrets sent over non-TLS MQTT and can be replayed until an administrator rotates them. Broker-side per-device credentials/topic ACLs, TLS, message signatures/replay protection, token expiry, MFA, rate limiting, production secrets management, and enforcement on arbitrary resources are not implemented.
