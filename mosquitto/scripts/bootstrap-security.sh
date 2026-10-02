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

# DynSec application errors are printed in the response but may still exit 0.
query_role_state() {
    role_name=$1
    if role_output=$(ctrl getRole "$role_name" 2>&1); then
        :
    else
        command_status=$?
        echo "Unable to query Dynamic Security role '$role_name' (exit $command_status): $role_output" >&2
        return 2
    fi

    if printf '%s\n' "$role_output" | grep -Fq 'Role not found'; then
        printf 'absent\n'
        return 0
    fi
    if printf '%s\n' "$role_output" | grep -F -x "Rolename: $role_name" >/dev/null; then
        printf 'present\n'
        return 0
    fi

    echo "Unexpected Dynamic Security getRole response for '$role_name': $role_output" >&2
    return 2
}

query_client_state() {
    client_username=$1
    if client_output=$(ctrl getClient "$client_username" 2>&1); then
        :
    else
        command_status=$?
        echo "Unable to query Dynamic Security client '$client_username' (exit $command_status): $client_output" >&2
        return 2
    fi

    if printf '%s\n' "$client_output" | grep -Fq 'Client not found'; then
        printf 'absent\n'
        return 0
    fi
    if printf '%s\n' "$client_output" | grep -F -x "Username: $client_username" >/dev/null; then
        printf 'present\n'
        return 0
    fi

    echo "Unexpected Dynamic Security getClient response for '$client_username': $client_output" >&2
    return 2
}

ensure_role() {
    role_name=$1
    role_state=$(query_role_state "$role_name")
    if [ "$role_state" = absent ]; then
        ctrl createRole "$role_name"
        role_state=$(query_role_state "$role_name")
    fi
    if [ "$role_state" != present ]; then
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

democlient_state=$(query_client_state democlient)
if [ "$democlient_state" = present ]; then
    ctrl disableClient democlient >/dev/null 2>&1 || true
fi

backend_client_state=$(query_client_state "$MQTT_BACKEND_USERNAME")
if [ "$backend_client_state" = present ]; then
    ctrl setClientPassword "$MQTT_BACKEND_USERNAME" "$MQTT_BACKEND_PASSWORD"
else
    ctrl createClient "$MQTT_BACKEND_USERNAME" -p "$MQTT_BACKEND_PASSWORD"
    backend_client_state=$(query_client_state "$MQTT_BACKEND_USERNAME")
fi
if [ "$backend_client_state" != present ]; then
    echo "Failed to create backend MQTT client: $MQTT_BACKEND_USERNAME" >&2
    exit 1
fi
ctrl removeClientRole "$MQTT_BACKEND_USERNAME" zt-backend-subscriber >/dev/null 2>&1 || true
ctrl addClientRole "$MQTT_BACKEND_USERNAME" zt-backend-subscriber 10
if ! ctrl getClient "$MQTT_BACKEND_USERNAME" | grep -Fq 'zt-backend-subscriber'; then
    echo "Backend subscriber role was not applied" >&2
    exit 1
fi
