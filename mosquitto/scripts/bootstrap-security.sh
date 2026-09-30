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

ensure_role() {
    role_name=$1
    if ! ctrl getRole "$role_name" >/dev/null 2>&1; then
        ctrl createRole "$role_name"
    fi
}

ensure_role zt-device-publisher
ctrl removeRoleACL zt-device-publisher publishClientSend 'iot/telemetry/%u' >/dev/null 2>&1 || true
ctrl addRoleACL zt-device-publisher publishClientSend 'iot/telemetry/%u' allow 10

ensure_role zt-backend-subscriber
ctrl removeRoleACL zt-backend-subscriber subscribePattern 'iot/telemetry/+' >/dev/null 2>&1 || true
ctrl addRoleACL zt-backend-subscriber subscribePattern 'iot/telemetry/+' allow 10
ctrl removeRoleACL zt-backend-subscriber publishClientReceive 'iot/telemetry/+' >/dev/null 2>&1 || true
ctrl addRoleACL zt-backend-subscriber publishClientReceive 'iot/telemetry/+' allow 10

# Keep ungranted operations fail-closed; the explicit backend receive ACL above remains valid.
ctrl setDefaultACLAccess publishClientReceive deny
ctrl setDefaultACLAccess unsubscribe deny

if ctrl getClient democlient >/dev/null 2>&1; then
    ctrl disableClient democlient >/dev/null 2>&1 || true
fi

if ctrl getClient "$MQTT_BACKEND_USERNAME" >/dev/null 2>&1; then
    ctrl setClientPassword "$MQTT_BACKEND_USERNAME" "$MQTT_BACKEND_PASSWORD"
else
    ctrl createClient "$MQTT_BACKEND_USERNAME" "$MQTT_BACKEND_PASSWORD"
fi
ctrl removeClientRole "$MQTT_BACKEND_USERNAME" zt-backend-subscriber >/dev/null 2>&1 || true
ctrl addClientRole "$MQTT_BACKEND_USERNAME" zt-backend-subscriber 10
