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

Returns HTTP `201 Created`. Types: `SENSOR`, `CAMERA`, `ACTUATOR`, `GATEWAY`.

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

Returns up to the most recent 100 evaluated access events, including requester/channel, device snapshot, resource/action, result/reason, matching-policy snapshot, and time. Every valid API decision and evaluated MQTT telemetry attempt is stored in `access_audits`; malformed requests rejected before evaluation are not.

## MQTT telemetry (Phase 5)

The local Mosquitto broker requires the shared local MQTT username/password configured in `.env`. It listens on the host loopback interface. The backend subscribes to `iot/telemetry/+`; the topic suffix must be a registered device code.

Message body:

```json
{
  "metric": "temperature",
  "value": 22.5,
  "unit": "C",
  "measuredAt": "2026-09-29T10:00:00Z"
}
```

`measuredAt` is optional; when omitted, receive time is used. The payload is limited to 2048 bytes and validates metric, numeric precision, unit, and timestamp. Each valid message is checked as a `DEVICE`/`MQTT` request for `device-telemetry` / `WRITE`. Only an `ALLOW` for an `ACTIVE` registered sensor/camera is stored; DENY is audited and the telemetry row is not written. Accepted messages update `devices.last_seen_at`.

### `GET /api/telemetry` — `ADMIN`, `SECURITY_ANALYST`

Returns the most recent 100 accepted telemetry samples. MQTT uses a single shared local credential in this prototype; it is not a per-device identity or ACL system.

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

Typical status codes: `400` invalid request, `401` missing/invalid authentication, `403` insufficient role for a management/read API, `404` missing resource, `409` duplicate identifier/name, and `500` unexpected server error. An evaluated access `DENY` is an HTTP `200` decision body, not an HTTP `403`.

Authentication-attempt and policy-change audits, dashboard endpoints, TLS, per-device MQTT credentials, and transparent enforcement on arbitrary IoT resources are not implemented yet.
