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

Send the token from login in the `Authorization` header:

```http
Authorization: Bearer <accessToken>
```

Returns the current user's safe profile. Missing, malformed, invalid, or expired tokens return `401 Unauthorized`.

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

## Phase 4 policy evaluation boundary

`PolicyEvaluationService.findApplicablePolicy(subject, resource, action)` selects among enabled exact matches. A matching explicit `DENY` takes precedence over `ALLOW`; otherwise the first matching `ALLOW` is returned. No match returns an empty result. This service is not yet exposed as an access-check endpoint and does not produce an `AccessDecision`; Phase 5 will combine it with authentication, device status, and default DENY.

## Error responses

Validation, authentication, authorization, and application errors use a consistent JSON structure:

```json
{
  "timestamp": "2026-09-28T10:00:00Z",
  "status": 403,
  "error": "ACCESS_DENIED",
  "message": "You do not have permission to perform this operation",
  "path": "/api/policies"
}
```

Typical status codes: `400` invalid request, `401` missing/invalid authentication, `403` insufficient role, `404` missing resource, `409` duplicate identifier/name, and `500` unexpected server error.

Access-check, audit, dashboard, and MQTT endpoints are planned but not implemented yet.
