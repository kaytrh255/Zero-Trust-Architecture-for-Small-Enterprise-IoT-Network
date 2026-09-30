# Software Design Description

## 1. Purpose

This modular-monolith prototype demonstrates Zero Trust for a small-enterprise IoT network: API authentication, device identity, least-privilege policy evaluation, explicit DENY precedence, default DENY, protected-resource enforcement, MQTT broker ACLs, replay-resistant telemetry persistence, and access-decision auditing. It is not a transparent gateway for arbitrary network traffic.

## 2. Scope and implemented phases

- Java 21, Spring Boot 3.x, Maven, PostgreSQL, Flyway, Docker Compose, and Mosquitto.
- User registration/login with BCrypt and JWT.
- Device and policy management with role-protected APIs.
- Zero Trust access decisions: registered device state, enabled exact-match policies, explicit DENY precedence, default DENY, and persistent audit records.
- A protected telemetry read route that fetches data only after ALLOW.
- Per-device MQTT credentials and topic ACLs through Mosquitto Dynamic Security, TLS with CA and hostname verification, backend device-status enforcement with auditable denials, and monotonic sequence replay protection.
- No dashboard, physical-device deployment, transparent packet gateway, or per-user device-ownership enforcement.

## 3. Architecture

The Spring Boot application owns REST APIs, JWT authentication, device and policy management, access decisions, the MQTT subscriber, telemetry ingestion, and audit/query services. PostgreSQL stores application records. Mosquitto authenticates MQTT usernames/passwords, binds each account to a client ID, and applies role ACLs. The backend has separate subscriber and Dynamic Security administration identities. Docker Compose creates the local CA/broker certificate and initializes Mosquitto Dynamic Security.

## 4. Authentication and management

`AuthService` hashes account passwords with BCrypt and issues signed JWTs. Protected requests reload current account status and role. The bootstrap admin and all local service secrets are environment-configured. Public registration always assigns `USER`.

Device creation issues a random 256-bit MQTT password. `MqttDynamicSecurityService` provisions/updates a Mosquitto client whose username is the normalized device code, client ID is fixed to the registered `mqttClientId`, and role is `zt-device-publisher`. Only the create/rotate response contains the plaintext MQTT password; PostgreSQL does not store it. Rotation changes the broker password. Broker accounts remain enabled across status changes so valid, topic-scoped telemetry reaches backend status validation and creates auditable `DEVICE_NOT_ACTIVE` decisions without persistence. Device code/client ID edits are rejected after provisioning so broker identity and ACLs cannot drift.

## 5. Zero Trust decision and protected-resource enforcement

`ZeroTrustDecisionService` loads the target device under a pessimistic database row lock and derives its type/status from PostgreSQL. Requester identity for HTTP comes from the JWT; the path/body device code names the target only. No owner-based access rule is implemented.

Decision order is requester role, device registration, ACTIVE status, exact enabled policy match, explicit DENY before ALLOW, and default DENY when no rule matches. MQTT replay validation occurs only after the device and policy are otherwise allowed; it does not supersede device-status DENY, explicit policy DENY, or default DENY. Each evaluated decision is saved with its context and policy snapshot. `ProtectedResourceService` queries target telemetry only after ALLOW; DENY returns no data.

## 6. MQTT/TLS design

The local Mosquitto broker exposes TLS listener `8883` only and is bound to host loopback. Compose creates a local CA and a server certificate with DNS SANs `mosquitto` and `localhost`, plus `127.0.0.1`. Java Paho clients build a trust store from `MQTT_CA_FILE` and set hostname verification on. The CA private key and generated certificates remain local and ignored by Git.

Mosquitto Dynamic Security denies anonymous clients and uses least-privilege roles:

- `zt-device-publisher`: publish send ACL `iot/telemetry/%u allow`.
- `zt-backend-subscriber`: subscribe and receive ACLs for `iot/telemetry/+` only.

The backend provisioning/admin account is separate from the backend subscriber. The admin credential is used only for Dynamic Security control operations and local broker bootstrap. Device credential/TLS/ACL rejection occurs at the broker and is not written into application access audits. Broker acceptance is not a policy decision: the backend still validates device status, policy, and replay before persistence.

## 7. Telemetry and replay protection

The telemetry JSON is limited to 2048 bytes and contains required positive `sequence`, metric, numeric value, unit, and optional `measuredAt`. It contains no application credential. The broker's username substitution binds a device to its own topic. The subscriber turns the authenticated topic suffix into a `DEVICE`/`MQTT` context and evaluates `device-telemetry` / `WRITE`.

For an otherwise-allowed message, the device row lock compares the incoming sequence with `devices.last_mqtt_sequence`. A sequence less than or equal to the stored high-water mark is audited as `REPLAYED_MESSAGE`; no telemetry is written. For ALLOW, the high-water mark advances and the telemetry row is inserted in the same transaction. The database enforces unique `(device_id, device_sequence)` as defense in depth. Flyway V6 assigns sequence numbers to preexisting Phase 6 telemetry and initializes each device high-water mark to the corresponding maximum.

## 8. Persistence and migrations

Flyway V1–V6 create/update users, devices, policies, audits, and telemetry. V6 removes the Phase 6 application credential hash because broker authentication is now authoritative, adds `devices.last_mqtt_sequence`, `device_telemetry.device_sequence`, the uniqueness/check constraints, `access_audits.message_sequence`, and the `REPLAYED_MESSAGE` reason. Hibernate uses `ddl-auto: validate`.

## 9. Testing and demo

Unit tests cover the policy/status order, explicit DENY/default DENY, replay high-water behavior, telemetry persistence only after ALLOW, device provisioning/rotation delegation, and protected-resource query gating. `Phase8ComposeIntegrationTest` is an opt-in JUnit end-to-end test (`PHASE8_INTEGRATION=true`) against an already running Compose stack. It checks trusted/untrusted TLS, rotated and invalid MQTT credentials/client IDs, per-device topic ACLs, ALLOW/replay/status/explicit DENY/default DENY ingestion outcomes and audit persistence, and the protected resource. It creates unique users/devices and removes its temporary policy, but intentionally leaves test records in the local database. Run it only against a disposable local stack. Manual expected outcomes are documented in README. Runtime verification was not possible here because Java/Maven and Docker Compose are unavailable.

## 10. Limitations

The protected HTTP route enforces only the demo telemetry resource. Its path identifies a target device, while requester identity comes from JWT; there is no device-ownership check. MQTT uses broker identities/ACLs and TLS but no application-level message signature. The locally generated CA is not production PKI; there is no automatic CA rotation, credential expiry, MFA, rate limiting, API-authentication/policy-change audit, dashboard, or enforcement over arbitrary external resources.
