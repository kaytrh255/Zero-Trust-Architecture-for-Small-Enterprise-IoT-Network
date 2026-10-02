ALTER TABLE mfa_security_audits
    ADD COLUMN actor_user_id BIGINT,
    ADD COLUMN actor_username VARCHAR(50),
    ADD CONSTRAINT fk_mfa_security_audits_actor FOREIGN KEY (actor_user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_mfa_security_audits_actor_snapshot CHECK (
        (actor_user_id IS NULL AND actor_username IS NULL)
        OR (actor_user_id IS NOT NULL AND actor_username IS NOT NULL)
    );

ALTER TABLE mfa_security_audits
    DROP CONSTRAINT ck_mfa_security_audits_operation,
    ADD CONSTRAINT ck_mfa_security_audits_operation CHECK (
        operation IN (
            'ENROLLMENT_STARTED',
            'ENABLED',
            'DISABLED',
            'RECOVERY_CODE_USED',
            'RECOVERY_CODES_ROTATED',
            'ADMIN_MFA_RECOVERY'
        )
    );

CREATE INDEX ix_mfa_security_audits_actor_changed_at
    ON mfa_security_audits (actor_user_id, changed_at DESC, id DESC);
CREATE INDEX ix_mfa_security_audits_actor_username_changed_at
    ON mfa_security_audits (lower(actor_username), changed_at DESC, id DESC);
