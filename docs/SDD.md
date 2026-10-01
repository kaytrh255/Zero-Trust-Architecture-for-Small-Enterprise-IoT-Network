# Software Design Description

## 1. Purpose

This modular-monolith prototype demonstrates Zero Trust for a small-enterprise IoT network: API authentication, device identity, least-privilege policy evaluation, explicit DENY precedence, default DENY, protected-resource enforcement, MQTT broker ACLs, replay-resistant telemetry persistence, access-decision auditing, and separate ownership-, device-status-, and policy-change histories. It is not a transparent gateway for arbitrary network traffic.

## 2. Scope and implemented phases

- Java 21, Spring Boot 3.x, Maven, PostgreSQL, Flyway, Docker Compose, and Mosquitto.
- User registration/login with BCrypt and JWT.
- Device and policy management with role-protected APIs.
- Zero Trust access decisions: registered device state, enabled exact-match policies, explicit DENY precedence, default DENY, and persistent audit records.
- A protected telemetry read route that fetches data only after policy ALLOW and device-owner verification; ownership denials are audited and do not query telemetry.
- ADMIN-controlled transfer of a device to an enabled USER; the authenticated USER must own the device to use the protected telemetry route.
- Successful owner changes are captured in a separate, immutable management-audit table with old/new owner snapshots, actor identity, and timestamp; admins/analysts can read the latest per-device history.
- Actual device-status transitions and policy CREATE/UPDATE/DELETE mutations are recorded in typed audit tables with authenticated actor, timestamp, and appropriate before/after state; these events share the business transaction. Admins/analysts can query status and policy history, including policy DELETE events after the live policy is removed.
- All audit-history reads use a stable page envelope with newest-first order, inclusive time filters, event-specific filters, zero-based pages, and a maximum page size of 100; the existing ADMIN/SECURITY_ANALYST authorization is retained.
- Per-device MQTT credentials and topic ACLs through Mosquitto Dynamic Security, TLS with CA and hostname verification, backend device-status enforcement with auditable denials, and monotonic sequence replay protection.
- No dashboard, physical-device deployment, or transparent packet gateway.

## 3. Architecture

The Spring Boot application owns REST APIs, JWT authentication, device and policy management, access decisions, the MQTT subscriber, telemetry ingestion, and access/ownership audit services. PostgreSQL stores application records. The runtime datasource uses a DML-only database login. Flyway runs in a separate one-shot Compose CLI container using a distinct migration login; that secret is not passed to the backend container. An idempotent PostgreSQL-admin bootstrap service creates/reconciles these roles and transfers existing `public` object ownership before migration/backend startup, including on existing volumes. Mosquitto authenticates MQTT usernames/passwords, binds each account to a client ID, and applies role ACLs. The backend has separate subscriber and Dynamic Security administration identities. Docker Compose creates the local CA/broker certificate and initializes Mosquitto Dynamic Security.

## 4. Authentication and management

`AuthService` hashes account passwords with BCrypt and issues signed JWTs. Protected requests reload current account status and role. The bootstrap admin and all local service secrets are environment-configured. Public registration always assigns `USER`.

Device creation assigns the authenticated ADMIN as owner and issues a random 256-bit MQTT password. `MqttDynamicSecurityService` provisions/updates a Mosquitto client whose username is the normalized device code, client ID is fixed to the registered `mqttClientId`, and role is unique to that device with a literal publish ACL for `iot/telemetry/{deviceCode}`. Only the create/rotate response contains the plaintext MQTT password; PostgreSQL does not store it. Rotation changes the broker password. ADMINs can transfer ownership through `PATCH /api/devices/{id}/owner`, but only to an enabled `USER`; owner identity is never accepted from a USER caller. Each actual owner change and its `device_ownership_audits` row commit atomically; same-owner no-ops and failed transfers do not generate events. The history snapshots the previous/new owner and the authenticated ADMIN. Broker accounts remain enabled across status changes so valid, topic-scoped telemetry reaches backend status validation and creates auditable `DEVICE_NOT_ACTIVE` decisions without persistence. Device code/client ID edits are rejected after provisioning so broker identity and ACLs cannot drift. Device status updates and revocation record a `device_status_audits` row only when the persisted status actually changes. `PolicyService` records a CREATE after-snapshot, UPDATE before/after snapshots, or DELETE before-snapshot. Both audit inserts join the business mutation transaction, so audit-write failure rolls the mutation back; rejected requests and no-op status changes do not produce history. `GET /api/devices/{id}/status-audits` and `GET /api/policies/{id}/audits` are restricted to ADMIN and SECURITY_ANALYST; policy history has no live-policy foreign key and remains queryable after deletion.

## 5. Zero Trust decision and protected-resource enforcement

`ZeroTrustDecisionService` loads the target device under a pessimistic database row lock and derives its type/status/owner from PostgreSQL. Requester identity for HTTP comes from the JWT; the path/body device code names the target only.

Decision order is requester role, device registration, ACTIVE status, exact enabled policy match, explicit DENY before ALLOW, and default DENY when no rule matches. For protected-resource reads only, a matching policy ALLOW is followed by an owner check; a mismatch returns `DEVICE_NOT_OWNED`. Thus ownership cannot override explicit DENY or default DENY. The generic `/api/access/check` endpoint remains a policy-decision demonstration, and MQTT ingestion retains its separate broker-identity/status/policy/replay flow. Each evaluated decision is saved with its context and policy snapshot. `ProtectedResourceService` queries target telemetry only after policy and ownership allow; DENY returns no data.

## 6. MQTT/TLS design

The local Mosquitto broker exposes TLS listener `8883` only and is bound to host loopback. Compose creates a local CA and a server certificate with DNS SANs `mosquitto` and `localhost`, plus `127.0.0.1`. Java Paho clients build a trust store from `MQTT_CA_FILE` and set hostname verification on. The CA private key and generated certificates remain local and ignored by Git.

Mosquitto Dynamic Security denies anonymous clients and uses least-privilege roles:

- Per-device `zt-device-{deviceCode}` role: one literal publish send ACL `iot/telemetry/{deviceCode} allow`.
- `zt-backend-subscriber`: subscribe and receive ACLs for `iot/telemetry/+` only.

The backend provisioning/admin account is separate from the backend subscriber. The admin credential is used only for Dynamic Security control operations and local broker bootstrap. Device credential/TLS/ACL rejection occurs at the broker and is not written into application access audits. Broker acceptance is not a policy decision: the backend still validates device status, policy, and replay before persistence.

## 7. Telemetry and replay protection

The telemetry JSON is limited to 2048 bytes and contains required positive `sequence`, metric, numeric value, unit, and optional `measuredAt`. It contains no application credential. The device's literal broker ACL binds its authenticated MQTT username to its own topic. The subscriber turns the authenticated topic suffix into a `DEVICE`/`MQTT` context and evaluates `device-telemetry` / `WRITE`.

For an otherwise-allowed message, the device row lock compares the incoming sequence with `devices.last_mqtt_sequence`. A sequence less than or equal to the stored high-water mark is audited as `REPLAYED_MESSAGE`; no telemetry is written. For ALLOW, the high-water mark advances and the telemetry row is inserted in the same transaction. The database enforces unique `(device_id, device_sequence)` as defense in depth. Flyway V6 assigns sequence numbers to preexisting Phase 6 telemetry and initializes each device high-water mark to the corresponding maximum.

## 8. Persistence and migrations

Flyway V1–V10 create/update users, devices, policies, audits, and telemetry. V6 removes the Phase 6 application credential hash because broker authentication is now authoritative, adds `devices.last_mqtt_sequence`, `device_telemetry.device_sequence`, the uniqueness/check constraints, `access_audits.message_sequence`, and the `REPLAYED_MESSAGE` reason. V7 adds `DEVICE_NOT_OWNED` to the access-audit reason constraint; ownership uses the existing `devices.owner_id` relation. V8 creates the dedicated ownership-transfer snapshot table. V9 adds device-status transition and policy-change audit tables with actor references, operation/snapshot validation, and per-entity history indexes. V10 adds a shared trigger that rejects UPDATE and DELETE operations on access, ownership, status, and policy audit rows. Phase 14 keeps the database identities separate: the runtime login has only application DML and does not own the schema, tables, sequences, views, or append-only trigger function; the migration login owns those objects and has `CREATE` on `public`. The role bootstrap reapplies this ownership/grant model idempotently and upgrades an existing volume without data loss. Phase 15 runs Flyway in a separate one-shot CLI container, leaving migration credentials out of the backend environment. Hibernate uses `ddl-auto: validate`.

## 9. Testing and demo

Unit tests cover the policy/status order, explicit DENY/default DENY, ownership checks and transfer-audit snapshots, device-status event attribution/no-op behavior, policy before/after snapshots and no-op updates, replay high-water behavior, telemetry persistence only after ALLOW, device provisioning/rotation delegation, and protected-resource query gating. `Phase10ComposeIntegrationTest` (`PHASE10_INTEGRATION=true`) checks TLS, broker credentials/ACLs, MQTT status/policy/replay decisions, owner transfer, and protected-resource enforcement. `Phase11ComposeIntegrationTest` (`PHASE11_INTEGRATION=true`) checks actual status-transition history, status no-op and invalid-request behavior, policy CREATE/UPDATE/DELETE snapshots, no audit for duplicate/conflicting/no-op mutations, actor/time fields, post-delete history lookup, ADMIN-only history access, page metadata, filters, time ranges, and invalid query rejection. `Phase10ComposeIntegrationTest` also checks page/filter behavior for access and ownership history. `Phase13AuditIntegrityComposeIntegrationTest` (`PHASE13_INTEGRATION=true`) injects temporary PostgreSQL failures to verify rollback of status, ownership, and policy mutations/audits; forces equal status-audit timestamps to check the ID-descending page tie-break; and verifies UPDATE/DELETE rejection on all audit tables. `Phase14DatabaseRolesComposeIntegrationTest` (`PHASE14_INTEGRATION=true`) verifies that the backend has no migration credentials, runtime and migration role flags/ownership, runtime DML through the backend API and directly against a legacy table, migration DDL, denied runtime CREATE/ALTER/TRUNCATE/trigger changes, and idempotent ownership transfer on an existing-volume-style fixture. The Compose integration tests use unique names and leave test records/audits, so run them only against a disposable local stack. GitHub Actions runs unit tests and all four opt-in integration classes on an ephemeral Compose stack for PRs and pushes, with Maven reports in the job summary and container logs on failure. Manual expected outcomes and local run instructions are documented in README.

## 10. Limitations

The protected HTTP route enforces ownership only for the demo telemetry resource; the decision-only endpoint and arbitrary external resources are not ownership-protected. MQTT uses broker identities/ACLs and TLS but no application-level message signature. The locally generated CA is not production PKI; there is no automatic CA rotation, credential expiry, MFA, rate limiting, API-authentication auditing, dashboard, or enforcement over arbitrary external resources.
