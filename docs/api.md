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

Successful response: HTTP `200 OK` with `accessToken`, `tokenType` (`Bearer`), `expiresInSeconds`, and a safe user profile. Wrong credentials return `401 Unauthorized` with a generic message.

### `GET /api/auth/me` — authenticated

Send the token from login in the `Authorization` header. Returns the current user's safe profile. Missing, malformed, invalid, or expired tokens return `401 Unauthorized`.

## Devices

Read endpoints permit roles `ADMIN` and `SECURITY_ANALYST`. Mutating endpoints require `ADMIN`. Creating a device assigns the authenticated administrator as owner; the request cannot assign another owner. New devices start `ACTIVE`. `deviceCode` is normalized to uppercase. Device code and MQTT client ID must be unique.

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

Returns HTTP `201 Created` with `{ "device": { ...safe device fields... }, "deviceToken": "<one-time token>" }`. The token is random, returned only once, and its BCrypt hash is stored. Types: `SENSOR`, `CAMERA`, `ACTUATOR`, `GATEWAY`.

### `POST /api/devices/{id}/credentials/rotate` — `ADMIN`

Returns HTTP `200 OK`, issues a new random MQTT application token for that device, returns it once, and invalidates the previous token. Use this route to provision the bootstrapped demo devices or recover a lost token. The response uses the same shape as device creation.

### `PUT /api/devices/{id}` — `ADMIN`

Replaces the device code and editable details using the same request body as POST. Status and owner are not changed by this endpoint.

### `PATCH /api/devices/{id}/status` — `ADMIN`

Request:

```json
{
  "status": "BLOCKED"
}
```

Allowed statuses: `ACTIVE`, `INACTIVE`, `BLOCKED`, `REVOKED`.

### `DELETE /api/devices/{id}` — `ADMIN`

Returns HTTP `204 No Content` and sets the device to `REVOKED`; the row is retained rather than physically removed.

## Policies

All policy routes require a bearer token. Reads permit `ADMIN` and `SECURITY_ANALYST`; mutations require `ADMIN`. Subject is normalized to uppercase and resource to lowercase; matching is exact (no wildcards).

### `GET /api/policies` — `ADMIN`, `SECURITY_ANALYST`

Returns all policies sorted by name.

### `GET /api/policies/{id}` — `ADMIN`, `SECURITY_ANALYST`

Returns a single policy or `404`.

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

Returns HTTP `201 Created`. Policy names are unique. Supported actions: `READ`, `WRITE`, `EXECUTE`; effects: `ALLOW`, `DENY`.

### `PUT /api/policies/{id}` — `ADMIN`

Replaces the policy fields using the same request body as POST.

### `DELETE /api/policies/{id}` — `ADMIN`

Physically removes the policy and returns HTTP `204 No Content`.

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

An evaluated `ALLOW` or `DENY` returns HTTP `200 OK`:

```json
{
  "auditId": 17,
  "decision": "ALLOW",
  "reason": "POLICY_ALLOW",
  "deviceCode": "SENSOR-001",
  "resource": "sensor-data",
  "action": "READ",
  "matchedPolicyId": 1,
  "matchedPolicyName": "Sensor Read Data",
  "evaluatedAt": "2026-09-29T10:00:00Z"
}
```

Other reasons: `EXPLICIT_DENY`, `NO_MATCHING_POLICY`, `DEVICE_NOT_FOUND`, `DEVICE_NOT_ACTIVE`, and `REQUESTER_ROLE_NOT_ALLOWED`. No matching policy defaults to DENY. Authentication failures still return `401`; invalid request fields return `400` before decision evaluation.

### `GET /api/access/audits` — `ADMIN`, `SECURITY_ANALYST`

Returns up to the most recent 100 evaluated access events, including requester/channel, device snapshot, resource/action, result/reason, matching-policy snapshot, and time. Every valid API decision, evaluated MQTT telemetry attempt, and syntactically valid MQTT message with an invalid device token is stored in `access_audits`; malformed requests rejected before evaluation are not.

## Protected telemetry resource (Phase 6)

### `GET /api/resources/devices/{deviceCode}/telemetry` — authenticated

This is a protected demo resource route, not just a decision check. The API derives the requester ID/name/role from the JWT and always evaluates the fixed `sensor-data` / `READ` operation against the registered target device. `USER`/`DEVICE` are eligible requester roles; an authenticated management role receives a recorded DENY. It queries telemetry only if the decision is `ALLOW`.

- `ALLOW`: HTTP `200`, with an `accessDecision` (including audit ID) and up to 100 telemetry samples for that device.
- `DENY`: HTTP `403`, with the denied `accessDecision` and an empty telemetry array. No telemetry query is run.
- Missing/invalid JWT: HTTP `401`.

For the seeded rules, an active sensor is allowed, the blocked `SENSOR-002` is denied by status, and `CAMERA-001` is denied because it has no `sensor-data` / `READ` policy. The decision and denial are audited.

## MQTT telemetry (Phase 6)

The local Mosquitto broker still requires the shared local MQTT username/password configured in `.env` and listens on the host loopback interface. The backend subscribes to `iot/telemetry/+`; the topic suffix must match the device code whose application token is in the body.

Message body:

```json
{
  "deviceToken": "<one-time token returned when the device is created/rotated>",
  "metric": "temperature",
  "value": 22.5,
  "unit": "C",
  "measuredAt": "2026-09-29T10:00:00Z"
}
```

`deviceToken` is a random 256-bit bearer credential issued once by `POST /api/devices` or `POST /api/devices/{id}/credentials/rotate`. Only its BCrypt hash is stored. Tokens are bound to the registered device code: using a token on another device's topic is rejected. Rotating a token invalidates the previous one. `measuredAt` is optional; when omitted, receive time is used. The payload is limited to 2048 bytes and validates the token, metric, numeric precision, unit, and timestamp.

After credential validation, each message is checked as a `DEVICE`/`MQTT` request for `device-telemetry` / `WRITE`. Only an `ALLOW` for an `ACTIVE` registered sensor/camera is stored; policy/status DENY is audited and the telemetry row is not written. A well-formed message with an invalid token is also audited as `INVALID_DEVICE_CREDENTIAL` without attributing it to the claimed device. Malformed topics/payloads are logged and discarded before policy evaluation. Accepted messages update `devices.last_seen_at`.

### `GET /api/telemetry` — `ADMIN`, `SECURITY_ANALYST`

Returns the most recent 100 accepted telemetry samples. The device token authenticates at the application ingestion layer; the broker still uses a shared local login and does not apply per-device topic ACLs. MQTT is non-TLS in this local Compose setup, so tokens should not be used over an untrusted network.

## Error responses

Validation, authentication, authorization, and application errors use a consistent JSON structure:

```json
{
  "timestamp": "2026-09-29T10:00:00Z",
  "status": 403,
  "error": "ACCESS_DENIED",
  "message": "You do not have permission to perform this operation",
  "path": "/api/policies"
}
```

Typical status codes: `400` invalid request, `401` missing/invalid authentication, `403` insufficient role or a DENY at the protected telemetry route, `404` missing resource, `409` duplicate identifier/name, and `500` unexpected server error. A DENY from `POST /api/access/check` is an HTTP `200` decision body. A protected resource DENY is HTTP `403` with a `ProtectedTelemetryResponse` containing the access decision and an empty telemetry array, not an `ApiError` body.

API authentication and policy-change audits, dashboard endpoints, TLS, broker-side per-device MQTT logins/topic ACLs, and transparent enforcement on arbitrary IoT resources are not implemented yet.
