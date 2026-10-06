ALTER TABLE devices
    ADD COLUMN last_mqtt_sequence BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_devices_last_mqtt_sequence CHECK (last_mqtt_sequence >= 0),
    DROP COLUMN mqtt_credential_hash;

ALTER TABLE device_telemetry
    ADD COLUMN device_sequence BIGINT;

WITH ordered_telemetry AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY device_id ORDER BY id) AS assigned_sequence
    FROM device_telemetry
)
UPDATE device_telemetry telemetry
SET device_sequence = ordered_telemetry.assigned_sequence
FROM ordered_telemetry
WHERE telemetry.id = ordered_telemetry.id;

ALTER TABLE device_telemetry
    ALTER COLUMN device_sequence SET NOT NULL,
    ADD CONSTRAINT ck_device_telemetry_sequence CHECK (device_sequence > 0),
    ADD CONSTRAINT uk_device_telemetry_device_sequence UNIQUE (device_id, device_sequence);

UPDATE devices device
SET last_mqtt_sequence = COALESCE(
    (SELECT MAX(telemetry.device_sequence)
     FROM device_telemetry telemetry
     WHERE telemetry.device_id = device.id),
    0
);

ALTER TABLE access_audits
    ADD COLUMN message_sequence BIGINT,
    DROP CONSTRAINT ck_access_audits_reason;

ALTER TABLE access_audits
    ADD CONSTRAINT ck_access_audits_reason CHECK (reason IN (
        'POLICY_ALLOW', 'EXPLICIT_DENY', 'NO_MATCHING_POLICY',
        'DEVICE_NOT_FOUND', 'DEVICE_NOT_ACTIVE', 'REQUESTER_ROLE_NOT_ALLOWED',
        'INVALID_DEVICE_CREDENTIAL', 'REPLAYED_MESSAGE'
    ));
