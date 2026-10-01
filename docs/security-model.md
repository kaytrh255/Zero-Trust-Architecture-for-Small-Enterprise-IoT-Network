# Security model (current implementation)

## Authentication

`AuthService` hashes application passwords with BCrypt before persistence. `users.password_hash` stores only the BCrypt hash. Registration/profile responses never expose it. Login uses Spring Security's `DaoAuthenticationProvider`; unknown usernames and incorrect passwords receive the same generic response. Every request that passes login DTO validation is written to `authentication_attempt_audits` with normalized username, outcome, and timestamp; successful events include the authenticated user ID. Passwords, JWTs, and client IP addresses are not stored. Only ADMIN and SECURITY_ANALYST can search this separate append-only history.

Successful login creates a signed JWT with subject, issue time, and expiry. The key comes from `JWT_SECRET`; `JWT_EXPIRATION_SECONDS` controls token lifetime. `JwtAuthenticationFilter` verifies signature/expiry and reloads the current account, role, and enabled status for protected requests. The API is stateless.

MQTT connections use TLS and verify the broker certificate against the generated local CA. The Java Paho clients use an explicit trust store and hostname verification; do not use an insecure TLS bypass. The Compose broker listens on TLS port `8883` only and is host-bound to loopback.

## PostgreSQL roles and audit-trigger ownership

PostgreSQL uses three distinct roles. `POSTGRES_USER` / `POSTGRES_ADMIN_PASSWORD` is the administrative identity used by the one-shot Compose `db-roles-init` service. `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD` is the non-superuser Flyway identity, supplied to the trusted database/bootstrap containers and the separate `db-migrate` CLI container. `DB_USERNAME` / `DB_PASSWORD` is the application runtime identity used by the Spring datasource for request, audit, and telemetry DML; the backend container does not receive the migration username or password.

The migration role owns the `public` schema, Flyway-managed tables/sequences/views, and the append-only trigger function. It has schema `CREATE` so Flyway can create/alter objects. The runtime role has only schema `USAGE`, `SELECT`/`INSERT`/`UPDATE`/`DELETE` on application tables, and sequence `USAGE`/`SELECT`; it is not an owner, has no role memberships or elevated cluster flags, and cannot create schema objects, temporary objects, alter tables, or disable/drop triggers. It can append audit rows; V10's triggers reject changes to the four business audit histories, and V11 adds the same append-only protection to authentication-attempt history. The PostgreSQL administrator, migration, and runtime passwords must all be distinct. Flyway migrations run in a separate one-shot `db-migrate` service before backend startup, so even a full backend-process compromise does not expose the migration credential through its environment. The migration and PostgreSQL administrator credentials remain trusted deployment/database principals.

Compose orders `db-roles-init` after PostgreSQL health, then `db-migrate`, then backend startup. The bootstrap and migration steps are one-shot services run when the stack is created/recreated. `db-roles-init` idempotently creates/reconciles both logins, removes runtime ownership on existing `public` application tables/sequences/views, clears excess runtime privileges on the database and `public` schema/tables/sequences, and transfers existing `public` table, sequence, view, schema, and audit-function ownership to the migration role. `db-migrate` runs the checked-in SQL migrations using the Flyway CLI, then exits before the backend starts. This upgrades a Phase 13 volume in place; do not delete the database volume to obtain the role separation. For an existing volume, keep `POSTGRES_ADMIN_PASSWORD` equal to the current stored administrator password so the bootstrap can connect. The database administrator and migration identity remain trusted DDL principals and can still disable the triggers; this is a privilege boundary against ordinary application-runtime SQL, not tamper-proof or immutable storage.

## Role-based management and access checks

The bootstrap administrator comes from local environment variables. Bootstrap never promotes an existing regular account. Public registration always assigns `USER` and cannot accept a role.

- `ADMIN`: manage devices/policies and device MQTT credentials/status; read devices, policies, authentication-attempt and access audits, ownership-transfer, status-change, and policy-change history, and telemetry.
- `SECURITY_ANALYST`: read devices, policies, authentication-attempt and access audits, ownership-transfer, status-change, and policy-change history, and telemetry.
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

Policy CREATE/UPDATE/DELETE operations are recorded in `policy_change_audits` with before/after snapshots and the authenticated ADMIN actor/timestamp. CREATE has only after state; UPDATE has both; DELETE has only before state. The mutation and event insert share a transaction; failed requests and semantically unchanged updates do not produce successful-mutation events. Policy history is keyed by a scalar policy ID rather than a policy foreign key, preserving DELETE history. `GET /api/devices/{id}/status-audits` and `GET /api/policies/{id}/audits` are restricted to ADMIN and SECURITY_ANALYST and use bounded 1–100 record pages. These typed management histories do not replace `access_audits` or `device_ownership_audits`. Phase 12's `page`, `size`, inclusive `from`/`to`, and event-specific filters affect read selection only: they do not grant new roles, alter stored events, or change policy evaluation. Page sizes are capped at 100 and malformed/reversed query ranges are rejected with HTTP `400`. Flyway V10 installs PostgreSQL triggers on the four business audit-history tables, and V11 adds the same row UPDATE/DELETE rejection to authentication-attempt history. Phase 14 gives the backend's runtime datasource no table/schema ownership or DDL privileges, so ordinary runtime SQL cannot disable/drop those triggers. The migration role and PostgreSQL administrator remain trusted DDL principals; this is not tamper-proof storage against them.

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

Every evaluated API decision and MQTT policy/status/replay decision is recorded in `access_audits`, including DENY outcomes. Protected-resource ownership failures are recorded as `DEVICE_NOT_OWNED` (including any matched ALLOW policy snapshot) before telemetry is queried. MQTT audit rows include the device sequence when one was evaluated. Successful owner transfers, actual device-status transitions, and policy CREATE/UPDATE/DELETE mutations are separately recorded in their typed management-audit tables, with actor identity and timestamp. Validated API login attempts are recorded separately in `authentication_attempt_audits`; they are not business access-decision events. Initial device ownership, status no-ops, semantically unchanged policy updates, and failed management mutations do not produce change events. Policy-history reads remain available by policy ID after the policy is deleted. `GET /api/auth/audits`, `GET /api/access/audits`, `GET /api/devices/{id}/ownership-audits`, `GET /api/devices/{id}/status-audits`, `GET /api/policies/{id}/audits`, and `GET /api/telemetry` are available only to `ADMIN` and `SECURITY_ANALYST`; audit-history pages contain at most 100 records. Broker authentication and ACL rejections and malformed MQTT messages rejected before decision evaluation are not database access-audit events.

The access-check endpoint remains a decision demonstration; `GET /api/resources/devices/{deviceCode}/telemetry` enforces ownership and policy for this demo resource only, not arbitrary network traffic. Device ownership is managed by ADMINs and applied to this protected HTTP route; it is not an MQTT ownership rule. MQTT uses authenticated per-device broker credentials, per-device topic ACLs, verified TLS, and monotonic sequence replay detection, but no application-level message signature. Credential expiry, MFA, rate limiting, production secrets management, and enforcement on arbitrary resources are not implemented.
