# API specification (implemented endpoints)

Base URL for local Compose: `http://localhost:8080`. JSON is used for request and response bodies. Health, registration, and login are public. All other routes require a bearer token.

## Health

### `GET /actuator/health` — public

Returns application health and database status. Successful response: HTTP `200`, overall `UP`, and `components.db.status` equal to `UP`.

## Authentication

### `POST /api/auth/register` — public

Request:

```json
{
  "username": "student1",
  "password": "StudentPass123!",
  "fullName": "Student One"
}
```

`username` must be 3–50 characters using letters, digits, dot, underscore, or hyphen. `password` must be 8–72 characters. `fullName` is required and limited to 100 characters. Registration always assigns role `USER`; clients cannot submit a role.

Successful response: HTTP `201 Created` with the user's `id`, `username`, `fullName`, `role`, and `enabled` fields. Password and password hash are never returned.

### `POST /api/auth/login` — public

Request:

```json
{
  "username": "student1",
  "password": "StudentPass123!"
}
```

Successful response: HTTP `200 OK` with `accessToken`, `tokenType` (`Bearer`), `expiresInSeconds`, and a safe user profile. Wrong credentials return `401 Unauthorized` with a generic message. Login requests admitted to authentication are recorded separately in authentication-attempt history; the audit stores no password, token, or client IP. By default, one socket peer may make 20 login requests per 60-second fixed window; `AUTH_LOGIN_RATE_LIMIT_MAX_ATTEMPTS` and `AUTH_LOGIN_RATE_LIMIT_WINDOW_SECONDS` configure the limit. Excess requests return `429 Too Many Requests` with a `Retry-After` header before password verification and do not create audit rows. Client IPs are used transiently by the in-memory limiter, not persisted in the audit table.

### `GET /api/auth/me` — authenticated

Send the token from login in the `Authorization` header. Returns the current user's safe profile. Missing, malformed, invalid, or expired tokens return `401 Unauthorized`.

### `GET /api/auth/audits` — `ADMIN`, `SECURITY_ANALYST` (Phase 16)

Searches validated login attempts with the common page envelope. Optional filters are `page`, `size`, inclusive `from` / `to`, `username` (case-insensitive), and `outcome` (`SUCCESS` or `FAILURE`). Failed attempts use the same generic `401` response for unknown users and incorrect passwords; their audit rows have no authenticated user ID. Successful rows include the authenticated user's ID. Passwords and JWTs are never stored or returned. Client IPs are not persisted in this history or returned; the process-local rate limiter keeps them only for the active window. `USER` callers receive `403 Forbidden`.

Example response item:

```json
{
  "id": 42,
  "attemptedUsername": "student1",
  "authenticatedUserId": 7,
  "outcome": "SUCCESS",
  "attemptedAt": "2026-10-01T12:00:00Z"
}
```

## Audit history pagination and filters (Phase 12)

All history endpoints return a stable page envelope instead of a bare array. The default is `page=0&size=100`; pages are zero-based, `size` must be 1–100, and records are ordered newest first with ID as the tie-breaker. `from` and `to` accept ISO-8601 instants (for example, `2026-10-01T00:00:00Z`) and are inclusive. Filters use full-string equality; username, device-code, and resource filters ignore case and surrounding whitespace. Invalid page/size, malformed filter values, or `from` later than `to` returns HTTP `400`.

```json
{
  "content": [{ "id": 41, "changedAt": "2026-10-01T12:00:00Z" }],
  "page": 0,
  "size": 100,
  "totalElements": 145,
  "totalPages": 2,
  "hasNext": true,
  "hasPrevious": false
}
```

`GET /api/auth/audits`, `GET /api/access/audits`, `GET /api/devices/{id}/ownership-audits`, `GET /api/devices/{id}/status-audits`, and `GET /api/policies/{id}/audits` use this envelope. Their access remains restricted to `ADMIN` and `SECURITY_ANALYST`. Flyway V10 makes the four business audit histories append-only; V11 adds append-only authentication-attempt history.

## Devices

Read endpoints permit roles `ADMIN` and `SECURITY_ANALYST`. Mutating endpoints require `ADMIN`. Creating a device assigns the authenticated administrator as owner; the create request cannot assign another owner. An administrator can later transfer ownership to an enabled `USER` using the owner endpoint below. New devices start `ACTIVE`. `deviceCode` is normalized to uppercase. Device code and MQTT client ID must be unique. Both are immutable after provisioning because the broker username/topic ACL and client-ID binding use them.

### `GET /api/devices` — `ADMIN`, `SECURITY_ANALYST`

Returns devices sorted by device code, with owner summary and timestamps.

### `GET /api/devices/{id}` — `ADMIN`, `SECURITY_ANALYST`

Returns one device, or `404` if the ID does not exist.

### `POST /api/devices` — `ADMIN`

Request:

```json
{
  "deviceCode": "SENSOR-003",
  "deviceName": "Temperature Sensor 3",
  "deviceType": "SENSOR",
  "ipAddress": "192.168.10.24",
  "mqttClientId": "SENSOR-003"
}
```

Returns HTTP `201 Created` with safe device fields plus one-time broker credentials:

```json
{
  "device": { "id": 3, "deviceCode": "SENSOR-003", "mqttClientId": "SENSOR-003" },
  "mqttUsername": "SENSOR-003",
  "mqttPassword": "<random 256-bit password>"
}
```

The backend provisions a Mosquitto Dynamic Security client and assigns the least-privilege device publishing role before returning. The password is not persisted by the backend and is not returned again. Types: `SENSOR`, `CAMERA`, `ACTUATOR`, `GATEWAY`.

### `POST /api/devices/{id}/credentials/rotate` — `ADMIN`

Returns HTTP `200 OK`, replaces the broker password, and returns the username/password once. The previous password becomes invalid. Use this route to provision the bootstrapped demo devices or recover a lost credential. The broker account remains enabled regardless of device status so a topic-scoped MQTT publish can reach backend status validation and be audited; a non-`ACTIVE` device's message is denied and not stored.

### `PUT /api/devices/{id}` — `ADMIN`

Updates the device name, type, and IP address using the same request shape as POST. `deviceCode` and `mqttClientId` must remain unchanged; attempts to change either return HTTP `409 CONFLICT`. Status and owner are not changed by this endpoint.

### `PATCH /api/devices/{id}/owner` — `ADMIN` (Phase 9)

Request:

```json
{
  "ownerUsername": "student1"
}
```

The account must exist, be enabled, and have role `USER`. Surrounding whitespace is trimmed and the username is normalized to lowercase before lookup. Returns HTTP `200 OK` with the updated `DeviceResponse`, including `ownerId` and `ownerUsername`. A successful change to a different owner writes a transfer-history event in the same database transaction; assigning the current owner again is an idempotent no-op and creates no event. A nonexistent username returns `404`; an ADMIN, `SECURITY_ANALYST`, `DEVICE`, or disabled account is rejected with `400`. Non-ADMIN callers receive `403`. Invalid requests never change the owner or create a successful-transfer event.

### `GET /api/devices/{id}/ownership-audits` — `ADMIN`, `SECURITY_ANALYST` (Phase 10)

Returns the page of successful ownership transfers for the device, newest first. Supports optional `from`, `to`, `page`, `size`, `changedByUsername`, and `newOwnerUsername` parameters. Each immutable event includes device ID/code, previous and new owner IDs/usernames as snapshots, the acting ADMIN's ID/username, and `changedAt`. The body uses the common page envelope:

```json
{
  "content": [
    {
      "id": 12,
      "deviceId": 3,
      "deviceCode": "SENSOR-001",
      "previousOwnerId": 1,
      "previousOwnerUsername": "admin",
      "newOwnerId": 9,
      "newOwnerUsername": "student1",
      "changedByUserId": 1,
      "changedByUsername": "admin",
      "changedAt": "2026-10-01T12:00:00Z"
    }
  ],
  "page": 0,
  "size": 100,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false,
  "hasPrevious": false
}
```

Device creation's initial owner assignment is not a transfer event; reassigning the same owner and failed requests also create no event. An unknown device returns `404`; other roles receive `403`. The events are stored in `device_ownership_audits`, separately from access-decision records.

### `PATCH /api/devices/{id}/status` — `ADMIN`

Request:

```json
{
  "status": "BLOCKED"
}
```

Allowed statuses: `ACTIVE`, `INACTIVE`, `BLOCKED`, `REVOKED`. Status is enforced in the backend on every delivered telemetry message. Broker accounts remain enabled so status failures create `DEVICE_NOT_ACTIVE` access-audit rows; those messages are not persisted and do not advance the replay high-water mark. A successful actual transition also inserts a `device_status_audits` event in the same database transaction, with device ID/code, previous/new status, authenticated administrator ID/username, and timestamp. Repeating the current status returns HTTP `200` but creates no event; a failed request creates no event.

### `GET /api/devices/{id}/status-audits` — `ADMIN`, `SECURITY_ANALYST` (Phase 11)

Returns a page of successful status transitions, newest first. Supports optional `from`, `to`, `page`, `size`, `newStatus`, and `changedByUsername` parameters. Each record has `id`, `deviceId`, `deviceCode`, `previousStatus`, `newStatus`, `changedByUserId`, `changedByUsername`, and `changedAt`. A missing device returns `404`; other roles receive `403`. This management history is stored separately from MQTT/API access-decision audits and ownership-transfer audits.

### `DELETE /api/devices/{id}` — `ADMIN`

Returns HTTP `204 No Content` and sets the device to `REVOKED`; the row and broker credential remain, but backend status validation denies and audits any valid scoped telemetry without storing it. If this changes the status, it also writes one status-history event atomically. Revoking an already-REVOKED device is a no-op for history. The device is retained rather than physically removed.

## Policies

All policy routes require a bearer token. Reads permit `ADMIN` and `SECURITY_ANALYST`; mutations require `ADMIN`. Subject is normalized to uppercase and resource to lowercase; matching is exact (no wildcards).

### `GET /api/policies` — `ADMIN`, `SECURITY_ANALYST`

Returns all policies sorted by name.

### `GET /api/policies/{id}` — `ADMIN`, `SECURITY_ANALYST`

Returns a single policy or `404`.

### `GET /api/policies/{id}/audits` — `ADMIN`, `SECURITY_ANALYST` (Phase 11)

Returns a page of CREATE/UPDATE/DELETE events for the policy ID. Supports optional `from`, `to`, `page`, `size`, `operation`, and `changedByUsername` parameters. This endpoint intentionally does not require the live policy to exist, so DELETE history remains readable after removal; an ID with no history has empty `content` and zero total elements. Each event includes operation, before/after policy snapshots, acting user ID/username, and `changedAt`. CREATE has only `after`, UPDATE has both snapshots, and DELETE has only `before`. Other roles receive `403`.

```json
{
  "content": [
    {
      "id": 41,
      "policyId": 12,
      "operation": "UPDATE",
      "before": { "name": "Sensor Read", "subject": "SENSOR", "resource": "sensor-data", "action": "READ", "effect": "ALLOW", "enabled": true, "description": "old rule" },
      "after": { "name": "Sensor Read", "subject": "SENSOR", "resource": "sensor-data", "action": "READ", "effect": "DENY", "enabled": false, "description": "changed rule" },
      "changedByUserId": 1,
      "changedByUsername": "admin",
      "changedAt": "2026-10-01T12:00:00Z"
    }
  ],
  "page": 0,
  "size": 100,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false,
  "hasPrevious": false
}
```

### `POST /api/policies` — `ADMIN`

Request:

```json
{
  "name": "Sensor Read Data Extra",
  "subject": "SENSOR",
  "resource": "sensor-data",
  "action": "READ",
  "effect": "ALLOW",
  "enabled": true,
  "description": "Allow sensors to read sensor data"
}
```

Returns HTTP `201 Created`. Policy names are unique. Supported actions: `READ`, `WRITE`, `EXECUTE`; effects: `ALLOW`, `DENY`. A successful CREATE and its `policy_change_audits` after-snapshot are committed atomically with the authenticated ADMIN actor and timestamp. Duplicate/invalid requests do not create an event.

### `PUT /api/policies/{id}` — `ADMIN`

Replaces the policy fields using the same request body as POST. An actual update and its before/after snapshots are one transaction. A semantically unchanged update returns HTTP `200` but does not add an event; validation, missing-policy, and duplicate-name failures do not add events.

### `DELETE /api/policies/{id}` — `ADMIN`

Physically removes the policy and returns HTTP `204 No Content`. The DELETE event with the last policy snapshot is committed atomically with removal. Audit rows do not have a foreign key to the live policy, preserving history after deletion.

## Access decisions (Phase 5)

### `POST /api/access/check` — authenticated

This endpoint requires a valid JWT and records a decision for each valid request. The user identity and role are taken from the token. The caller cannot submit a requester ID, role, device type, or device status.

Request:

```json
{
  "deviceCode": "SENSOR-001",
  "resource": "sensor-data",
  "action": "READ"
}
```

`deviceCode` must be 1–64 allowed characters; `resource` must be 1–100 allowed characters; `action` is `READ`, `WRITE`, or `EXECUTE`. Device code is normalized to uppercase and resource to lowercase. The device type/subject and status are looked up from PostgreSQL.

Eligible API requester roles: `USER` and `DEVICE`. An authenticated `ADMIN` or `SECURITY_ANALYST` gets a business decision `DENY` with reason `REQUESTER_ROLE_NOT_ALLOWED`; this is not an HTTP `403` because the decision was evaluated and audited.

Decision order:

1. Require an eligible requester role.
2. Require a registered device (`DEVICE_NOT_FOUND` otherwise).
3. Require `ACTIVE` device status (`DEVICE_NOT_ACTIVE` otherwise).
4. Evaluate enabled exact subject/resource/action policies; explicit matching `DENY` wins.
5. If no rule matches, return default `DENY` (`NO_MATCHING_POLICY`).

An evaluated `ALLOW` or `DENY` returns HTTP `200 OK` with decision, reason, policy snapshot, timestamp, and audit ID. Authentication failures return `401`; invalid request fields return `400` before decision evaluation.

### `GET /api/access/audits` — `ADMIN`, `SECURITY_ANALYST`

Returns a page of evaluated access events, including requester/channel, device snapshot, resource/action, result/reason, optional MQTT `messageSequence`, matching-policy snapshot, and time. Supports optional `from`, `to`, `page`, `size`, `decision`, `reason`, `channel`, `action`, `deviceCode`, `requesterUsername`, and `resource` filters. Evaluated MQTT replay attempts have reason `REPLAYED_MESSAGE` and include the repeated sequence. Broker authentication/ACL failures and malformed MQTT messages rejected before policy evaluation are in Mosquitto/backend logs, not `access_audits`.

## Protected telemetry resource (Phases 6 and 9)

### `GET /api/resources/devices/{deviceCode}/telemetry` — authenticated

This is a protected demo resource route, not just a decision check. The API derives requester ID/name/role from the JWT and always evaluates the fixed `sensor-data` / `READ` operation against the registered target device. API requester-role checks remain in force; management roles receive a recorded DENY. In addition to an active device and matching ALLOW policy, the authenticated requester must match the device owner, which ADMINs assign only to enabled `USER` accounts. The route queries telemetry only after all checks allow access.

Decision order is requester role, registered device, `ACTIVE` status, exact policy (`EXPLICIT_DENY` before ALLOW; no match is default DENY), then owner verification for a policy-allowed read. This preserves `DEVICE_NOT_ACTIVE`, explicit DENY, and `NO_MATCHING_POLICY` precedence. The generic `/api/access/check` endpoint remains a policy-decision demonstration and does not authorize or fetch a protected resource.

- `ALLOW`: HTTP `200`, with an `accessDecision` (including audit ID) and up to 100 telemetry samples for that device.
- `DEVICE_NOT_OWNED`: HTTP `403`, with an audited denied `accessDecision` and an empty telemetry array. No telemetry query is run.
- Other evaluated `DENY`: HTTP `403`, with reason/audit ID and an empty telemetry array. No telemetry query is run.
- Missing/invalid JWT: HTTP `401`.

A non-owner is denied even when the device is active and the policy matches ALLOW. For the seeded rules, an active sensor is policy-allowed (so ownership is then checked), the blocked `SENSOR-002` is denied as `DEVICE_NOT_ACTIVE`, and `CAMERA-001` is denied as `NO_MATCHING_POLICY` before ownership is considered. The JWT supplies requester identity; the path names the target device. A `DEVICE_NOT_OWNED` audit includes the matched ALLOW policy snapshot, documenting that policy alone was insufficient.

## MQTT telemetry (Phase 7)

The local Compose broker listens on loopback TLS port `8883` only. MQTT clients must trust `mosquitto/tls/ca.crt` and verify the broker hostname. No plaintext `1883` listener is configured. The backend subscriber uses a dedicated restricted account; device clients use their own broker credentials and fixed MQTT client IDs.

`POST /api/devices` and `POST /api/devices/{id}/credentials/rotate` return `mqttUsername` (the uppercase `deviceCode`) and a random `mqttPassword` once. Mosquitto Dynamic Security checks username/password/client ID and assigns the device a role that can publish only to `iot/telemetry/{that username}`. The backend subscriber can subscribe/receive only on `iot/telemetry/+`. Anonymous connections, unmatched subscriptions/publishes/receives, and retained messages are disabled/denied. Broker-level authentication/ACL failures occur before backend ingestion and are not database access-audit events.

Message body:

```json
{
  "sequence": 1,
  "metric": "temperature",
  "value": 22.5,
  "unit": "C",
  "measuredAt": "2026-09-30T10:00:00Z"
}
```

`sequence` is required, positive, and strictly greater than that device's last accepted sequence. The backend row-locks the device and performs the existing `ACTIVE` status and `device-telemetry` / `WRITE` policy checks before accepting a new sequence. Explicit policy DENY and no-match default DENY remain in force. A repeated/lower sequence is audited as `DENY` / `REPLAYED_MESSAGE`; it is not stored. The sequence high-water update and telemetry insert are in one transaction, and `(device_id, device_sequence)` is unique. Existing Phase 6 telemetry is assigned increasing per-device sequences by Flyway V6; start new publishers after the migrated high-water mark. `measuredAt` is optional; receive time is used when omitted. Payload size is limited to 2048 bytes.

### `GET /api/telemetry` — `ADMIN`, `SECURITY_ANALYST`

Returns the most recent 100 accepted telemetry samples, including `deviceSequence`. MQTT credential secrets are owned by Mosquitto Dynamic Security; PostgreSQL stores the current accepted sequence, not a plaintext MQTT password.

## Error responses

Validation, authentication, authorization, and application errors use a consistent JSON structure:

```json
{
  "timestamp": "2026-09-30T10:00:00Z",
  "status": 403,
  "error": "ACCESS_DENIED",
  "message": "You do not have permission to perform this operation",
  "path": "/api/policies"
}
```

Typical status codes: `400` invalid request, `401` missing/invalid authentication or rejected login, `403` insufficient role or a DENY at the protected telemetry route, `404` missing resource, `409` duplicate/immutable identity, `429` login rate limit exceeded (includes `Retry-After`), and `500` unexpected server error. A DENY from `POST /api/access/check` is an HTTP `200` decision body. A protected resource DENY is HTTP `403` with a `ProtectedTelemetryResponse` containing the access decision and an empty telemetry array, not an `ApiError` body.

Dashboard endpoints, application-level MQTT message signatures, and transparent enforcement on arbitrary IoT resources are not implemented. Phase 11 provides separate audit history for successful device-status transitions and policy mutations; Phase 16 adds a distinct, append-only history for admitted API login attempts; Phase 17 applies a bounded, process-local source-IP limiter, not a distributed production gateway.
