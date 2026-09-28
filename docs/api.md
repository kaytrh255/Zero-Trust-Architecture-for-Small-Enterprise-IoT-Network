# API specification (implemented endpoints)

Base URL for local Compose: `http://localhost:8080`. JSON is used for request and response bodies. The health endpoint is public; register and login are public; `GET /api/auth/me` requires a bearer token.

## Health

### `GET /actuator/health`

Returns application health and the database status. Successful response: HTTP `200`, overall `UP`, and `components.db.status` equal to `UP`.

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

Successful response: HTTP `200 OK`:

```json
{
  "accessToken": "<signed JWT>",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600,
  "user": {
    "id": 1,
    "username": "student1",
    "fullName": "Student One",
    "role": "USER",
    "enabled": true
  }
}
```

Wrong credentials return `401 Unauthorized` with a generic message.

### `GET /api/auth/me` — authenticated

Send the token from login in the `Authorization` header:

```http
Authorization: Bearer <accessToken>
```

Returns the current user's safe profile. Missing, malformed, invalid, or expired tokens return `401 Unauthorized`.

## Error response

Validation, authentication, and application errors use this structure:

```json
{
  "timestamp": "2026-09-28T10:00:00Z",
  "status": 401,
  "error": "UNAUTHORIZED",
  "message": "Authentication is required or the bearer token is invalid or expired",
  "path": "/api/auth/me"
}
```

Implemented status codes include `400` validation/request errors, `401` authentication failures, `403` authorization failures, `404` missing resources, `409` duplicate usernames/unique values, and `500` unexpected server errors.

Device, policy, access-check, audit, and dashboard endpoints are planned but not implemented yet.
