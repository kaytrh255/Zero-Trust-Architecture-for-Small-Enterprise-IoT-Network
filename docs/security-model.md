# Security model (current implementation)

## Authentication

`AuthService` hashes application passwords with BCrypt before persistence. `users.password_hash` stores only the BCrypt hash. Registration/profile responses never expose it. Login uses Spring Security's `DaoAuthenticationProvider`; unknown usernames and incorrect passwords receive the same generic response.

Successful login creates a signed JWT with subject, issue time, and expiry. The key comes from `JWT_SECRET`; `JWT_EXPIRATION_SECONDS` controls token lifetime. `JwtAuthenticationFilter` verifies signature/expiry and reloads the current account, role, and enabled status for protected requests. The API is stateless.

MQTT connections use TLS and verify the broker certificate against the generated local CA. The Java Paho clients use an explicit trust store and hostname verification; do not use an insecure TLS bypass. The Compose broker listens on TLS port `8883` only and is host-bound to loopback.

## Role-based management and access checks

The bootstrap administrator comes from local environment variables. Bootstrap never promotes an existing regular account. Public registration always assigns `USER` and cannot accept a role.

- `ADMIN`: manage devices/policies and device MQTT credentials/status; read devices, policies, access audits, ownership-transfer, status-change, and policy-change history, and telemetry.
- `SECURITY_ANALYST`: read devices, policies, access audits, ownership-transfer, status-change, and policy-change history, and telemetry.
- `USER` / `DEVICE`: eligible requester roles for API access-decision evaluation; protected telemetry reads additionally require an enabled `USER` owner. They cannot manage policies/devices or read management/audit/telemetry history endpoints.

`POST /api/access/check` and the protected telemetry route derive requester ID/name/role from the bearer-token principal. The caller cannot claim another account or submit trusted device type/status. Management roles receive a recorded business `DENY` (`REQUESTER_ROLE_NOT_ALLOWED`) rather than overriding resource policy. The decision endpoint returns HTTP 200 for an evaluated DENY; the protected telemetry route returns HTTP 403 and no resource data on DENY. For this route, an enabled `USER` must also own the target device; only an ADMIN can transfer ownership, and only to an enabled `USER`. The generic decision endpoint remains a policy demonstration and does not fetch protected data or enforce ownership. Missing/invalid authentication remains HTTP 401.

## Decision order and policy

`ZeroTrustDecisionService` loads the registered device by normalized device code while holding a pessimistic row lock. Device type is the policy subject; the client supplies only the device code, resource, and action.

1. Verify the requester role is eligible for the channel (`USER`/`DEVICE` for API; `DEVICE` internally for MQTT).
2. Require a registered device.
3. Require status `ACTIVE`; `INACTIVE`, `BLOCKED`, and `REVOKED` are denied.
4. Query enabled exact subject/resource/action policies. An explicit `DENY` is selected before `ALLOW`.
5. If no policy matches, return default `DENY`.
6. For an otherwise-allowed protected-resource API read, require the JWT requester to match the device owner; otherwise return `DENY` (`DEVICE_NOT_OWNED`). This check does not apply to MQTT ingestion or the decision-only `/api/access/check` endpoint.
7. For an otherwise-allowed MQTT request, require a sequence greater than the device's last accepted sequence; stale/repeated messages are denied as `REPLAYED_MESSAGE`.
8. Persist the outcome/reason and context in `access_audits`.

An MQTT high-water mark is advanced only for an allowed message and in the same database transaction as telemetry persistence. Device row locking serializes concurrent messages; the telemetry table also enforces uniqueness on `(device_id, device_sequence)`. A message denied by device status or policy is not persisted. Explicit policy DENY and default DENY retain precedence over replay acceptance.

No role, device status, or policy miss can be overridden by a matching ALLOW rule. `ProtectedResourceService` queries recent telemetry only after the decision service returns ALLOW; on DENY, it returns an empty telemetry array without running a telemetry query. The policy table does not support wildcards or contextual expressions.

## Management-change audit history

Successful actual device-status transitions are stored in `device_status_audits` with previous/new status, device ID/code, the authenticated ADMIN ID/username, and timestamp. `PATCH /api/devices/{id}/status` and `DELETE /api/devices/{id}` update status and insert the event in one transaction. Repeating the current status or revoking an already-revoked device is a no-op and creates no event. The status audit is separate from the per-message `DEVICE_NOT_ACTIVE` access-decision audit.

Policy CREATE/UPDATE/DELETE operations are recorded in `policy_change_audits` with before/after snapshots and the authenticated ADMIN actor/timestamp. CREATE has only after state; UPDATE has both; DELETE has only before state. The mutation and event insert share a transaction; failed requests and semantically unchanged updates do not produce successful-mutation events. Policy history is keyed by a scalar policy ID rather than a policy foreign key, preserving DELETE history. `GET /api/devices/{id}/status-audits` and `GET /api/policies/{id}/audits` are restricted to ADMIN and SECURITY_ANALYST and use bounded 1–100 record pages. These typed management histories do not replace `access_audits` or `device_ownership_audits`. Phase 12's `page`, `size`, inclusive `from`/`to`, and event-specific filters affect read selection only: they do not grant new roles, alter stored events, or change policy evaluation. Page sizes are capped at 100 and malformed/reversed query ranges are rejected with HTTP `400`. Flyway V10 installs PostgreSQL triggers on all four audit-history tables that reject row UPDATE and DELETE operations, keeping application-level history append-only. This protects normal DML paths; it is not tamper-proof storage against an operator with database-owner privileges who can alter or disable the triggers.

## MQTT credentials and ACLs

Mosquitto Dynamic Security is the broker authentication/authorization point. Each device has its own random 256-bit password, username equal to its normalized device code, and registered MQTT client ID. The create/rotate API returns the MQTT username and password once. The broker, not PostgreSQL, stores the MQTT credential verifier. Rotation replaces the broker password. Device accounts remain enabled across status changes: the per-device topic ACL still bounds publish scope, then the backend validates status and records `DEVICE_NOT_ACTIVE` for non-active devices before discarding their messages. The backend uses a different subscriber account and a separate role.

- Each device gets a unique role `zt-device-{deviceCode}` with one literal `publishClientSend iot/telemetry/{deviceCode} allow` ACL. The broker therefore rejects cross-device topic publishes.
- Backend role: subscribe/receive only on `iot/telemetry/+`.
- Anonymous connection: disabled.
- Unmatched publish/subscribe/receive/unsubscribe: denied by default (with explicit backend receive/subscription ACLs).
- Retained publishes: disabled for this prototype.

Wrong credentials, a mismatched client ID, or a publish to another device's topic are rejected at the broker before the backend sees the message; those attempts appear in Mosquitto logs, not in `access_audits`. Broker access does not override the backend's active-device check, policy evaluation, explicit DENY, default DENY, or replay check.

The JSON telemetry body contains no bearer secret. It carries a required positive sequence number. The backend audits policy/status/replay outcomes and stores accepted samples only. Malformed payloads rejected before decision evaluation are logged by the subscriber and are not access-audit events.

The generated CA/certificates are for a local demo, not a PKI deployment. Keep `.env` and `mosquitto/tls/ca.key` private; local TLS files are ignored by Git. Do not expose the broker or admin credentials to untrusted networks. There is no device certificate authentication, message signing, credential expiry, automated CA rotation, or production secret-management system.

## Audit and limitations

Every evaluated API decision and MQTT policy/status/replay decision is recorded in `access_audits`, including DENY outcomes. Protected-resource ownership failures are recorded as `DEVICE_NOT_OWNED` (including any matched ALLOW policy snapshot) before telemetry is queried. MQTT audit rows include the device sequence when one was evaluated. Successful owner transfers, actual device-status transitions, and policy CREATE/UPDATE/DELETE mutations are separately recorded in their typed management-audit tables, with actor identity and timestamp. Initial device ownership, status no-ops, semantically unchanged policy updates, and failed operations do not produce change events. Policy-history reads remain available by policy ID after the policy is deleted. `GET /api/access/audits`, `GET /api/devices/{id}/ownership-audits`, `GET /api/devices/{id}/status-audits`, `GET /api/policies/{id}/audits`, and `GET /api/telemetry` are available only to `ADMIN` and `SECURITY_ANALYST`; audit-history pages contain at most 100 records. Broker authentication and ACL rejections, malformed MQTT messages rejected before decision evaluation, and API authentication attempts are not database access-audit events.

The access-check endpoint remains a decision demonstration; `GET /api/resources/devices/{deviceCode}/telemetry` enforces ownership and policy for this demo resource only, not arbitrary network traffic. Device ownership is managed by ADMINs and applied to this protected HTTP route; it is not an MQTT ownership rule. MQTT uses authenticated per-device broker credentials, per-device topic ACLs, verified TLS, and monotonic sequence replay detection, but no application-level message signature. API-authentication auditing, credential expiry, MFA, rate limiting, production secrets management, and enforcement on arbitrary resources are not implemented.
