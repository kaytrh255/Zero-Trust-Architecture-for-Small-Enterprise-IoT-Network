#!/bin/sh
set -eu

: "${MQTT_DYNSEC_ADMIN_USERNAME:?MQTT_DYNSEC_ADMIN_USERNAME is required}"
: "${MQTT_DYNSEC_ADMIN_PASSWORD:?MQTT_DYNSEC_ADMIN_PASSWORD is required}"
: "${MQTT_BACKEND_USERNAME:?MQTT_BACKEND_USERNAME is required}"
: "${MQTT_BACKEND_PASSWORD:?MQTT_BACKEND_PASSWORD is required}"

if [ "$MQTT_DYNSEC_ADMIN_USERNAME" = "democlient" ] \
        || [ "$MQTT_BACKEND_USERNAME" = "$MQTT_DYNSEC_ADMIN_USERNAME" ] \
        || [ "$MQTT_BACKEND_USERNAME" = "democlient" ]; then
    echo "Broker admin, backend, and reserved democlient usernames must be distinct" >&2
    exit 1
fi

ctrl() {
    mosquitto_ctrl \
        --cafile /mosquitto/tls/ca.crt \
        -h mosquitto -p 8883 \
        -u "$MQTT_DYNSEC_ADMIN_USERNAME" \
        -P "$MQTT_DYNSEC_ADMIN_PASSWORD" \
        dynsec "$@"
}

role_exists() {
    ctrl listRoles | grep -F -x "$1" >/dev/null
}

client_exists() {
    ctrl listClients | grep -F -x "$1" >/dev/null
}

ensure_role() {
    role_name=$1
    if ! role_exists "$role_name"; then
        ctrl createRole "$role_name"
    fi
    if ! role_exists "$role_name"; then
        echo "Failed to create Dynamic Security role: $role_name" >&2
        exit 1
    fi
}

# Device publisher roles are created per device with a literal topic ACL by the backend.
ensure_role zt-backend-subscriber
ctrl removeRoleACL zt-backend-subscriber subscribePattern 'iot/telemetry/+' >/dev/null 2>&1 || true
ctrl addRoleACL zt-backend-subscriber subscribePattern 'iot/telemetry/+' allow 10
ctrl removeRoleACL zt-backend-subscriber publishClientReceive 'iot/telemetry/+' >/dev/null 2>&1 || true
ctrl addRoleACL zt-backend-subscriber publishClientReceive 'iot/telemetry/+' allow 10
if ! ctrl getRole zt-backend-subscriber | grep -F 'subscribePattern' | grep -F 'iot/telemetry/+' | grep -Fq 'allow'; then
    echo "Backend subscriber role subscription ACL was not applied" >&2
    exit 1
fi
if ! ctrl getRole zt-backend-subscriber | grep -F 'publishClientReceive' | grep -F 'iot/telemetry/+' | grep -Fq 'allow'; then
    echo "Backend subscriber role receive ACL was not applied" >&2
    exit 1
fi

# Keep ungranted operations fail-closed; the explicit backend receive ACL above remains valid.
ctrl setDefaultACLAccess publishClientReceive deny
ctrl setDefaultACLAccess unsubscribe deny

if client_exists democlient; then
    ctrl disableClient democlient >/dev/null 2>&1 || true
fi

if client_exists "$MQTT_BACKEND_USERNAME"; then
    ctrl setClientPassword "$MQTT_BACKEND_USERNAME" "$MQTT_BACKEND_PASSWORD"
else
    ctrl createClient "$MQTT_BACKEND_USERNAME" -p "$MQTT_BACKEND_PASSWORD"
fi
if ! client_exists "$MQTT_BACKEND_USERNAME"; then
    echo "Failed to create backend MQTT client: $MQTT_BACKEND_USERNAME" >&2
    exit 1
fi
ctrl removeClientRole "$MQTT_BACKEND_USERNAME" zt-backend-subscriber >/dev/null 2>&1 || true
ctrl addClientRole "$MQTT_BACKEND_USERNAME" zt-backend-subscriber 10
if ! ctrl getClient "$MQTT_BACKEND_USERNAME" | grep -Fq 'zt-backend-subscriber'; then
    echo "Backend subscriber role was not applied" >&2
    exit 1
fi
