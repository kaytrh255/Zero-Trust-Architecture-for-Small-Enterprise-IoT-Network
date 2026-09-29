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

Read endpoints permit roles `ADMIN` and `SECURITY_ANALYST`. Mutating endpoints require `ADMIN`. Creating a device assigns the authenticated administrator as owner; the request cannot assign another owner. New devices start `ACTIVE`. `deviceCode` is normalized to uppercase. Both device code and MQTT client ID must be unique.

### `GET /api/devices` — `ADMIN`, `SECURITY_ANALYST`

Returns the device list sorted by device code, with owner summary and timestamps.

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

Replaces the device's code and editable details using the same request body as POST. Status and owner are not changed by this endpoint.

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

### Device error responses

- `400`: invalid/missing fields or an invalid enum value.
- `401`: missing or invalid token.
- `403`: valid token without the required role.
- `404`: device ID not found.
- `409`: duplicate device code or MQTT client ID.

The demo database seeds `SENSOR-001` (`ACTIVE`), `CAMERA-001` (`ACTIVE`), and `SENSOR-002` (`BLOCKED`) if they do not already exist. Device status is currently managed and displayed; protected IoT resource decisions that deny blocked/revoked devices are planned for the policy/access-decision phase.

## Error response

```json
{
  "timestamp": "2026-09-28T10:00:00Z",
  "status": 403,
  "error": "ACCESS_DENIED",
  "message": "You do not have permission to perform this operation",
  "path": "/api/devices"
}
```

Policy, access-check, audit, dashboard, and MQTT endpoints are planned but not implemented yet.
