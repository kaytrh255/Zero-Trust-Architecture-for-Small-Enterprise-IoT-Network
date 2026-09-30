#!/bin/sh
set -eu

DATA_DIR=/mosquitto/data
TLS_DIR=/mosquitto/tls

: "${MQTT_DYNSEC_ADMIN_USERNAME:?MQTT_DYNSEC_ADMIN_USERNAME is required}"
: "${MQTT_DYNSEC_ADMIN_PASSWORD:?MQTT_DYNSEC_ADMIN_PASSWORD is required}"
mkdir -p "$DATA_DIR" "$TLS_DIR"

if [ ! -s "$DATA_DIR/dynamic-security.json" ]; then
    mosquitto_ctrl dynsec init \
        "$DATA_DIR/dynamic-security.json" \
        "$MQTT_DYNSEC_ADMIN_USERNAME" \
        "$MQTT_DYNSEC_ADMIN_PASSWORD"
fi

if [ ! -s "$TLS_DIR/ca.crt" ] || [ ! -s "$TLS_DIR/ca.key" ] \
        || [ ! -s "$TLS_DIR/server.crt" ] || [ ! -s "$TLS_DIR/server.key" ]; then
    rm -f "$TLS_DIR/ca.crt" "$TLS_DIR/ca.key" \
        "$TLS_DIR/server.crt" "$TLS_DIR/server.key" \
        "$TLS_DIR/server.csr" "$TLS_DIR/server.ext" "$TLS_DIR/ca.srl"

    openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 3650 \
        -keyout "$TLS_DIR/ca.key" \
        -out "$TLS_DIR/ca.crt" \
        -subj "/CN=Zero Trust local MQTT CA" \
        -addext "basicConstraints=critical,CA:TRUE" \
        -addext "keyUsage=critical,keyCertSign,cRLSign"

    openssl req -new -newkey rsa:2048 -nodes -sha256 \
        -keyout "$TLS_DIR/server.key" \
        -out "$TLS_DIR/server.csr" \
        -subj "/CN=mosquitto"

    cat > "$TLS_DIR/server.ext" <<'EOF'
[server_ext]
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:mosquitto,DNS:localhost,IP:127.0.0.1
EOF

    openssl x509 -req -in "$TLS_DIR/server.csr" \
        -CA "$TLS_DIR/ca.crt" -CAkey "$TLS_DIR/ca.key" -CAcreateserial \
        -out "$TLS_DIR/server.crt" -days 825 -sha256 \
        -extfile "$TLS_DIR/server.ext" -extensions server_ext
    rm -f "$TLS_DIR/server.csr" "$TLS_DIR/server.ext" "$TLS_DIR/ca.srl"
fi

chmod 600 "$TLS_DIR/ca.key"
chmod 644 "$TLS_DIR/ca.crt" "$TLS_DIR/server.crt"
chmod 640 "$TLS_DIR/server.key"
chown 1883:1883 "$DATA_DIR"
chown -R 1883:1883 "$DATA_DIR"
chown 1883:1883 "$TLS_DIR/server.crt" "$TLS_DIR/server.key"
