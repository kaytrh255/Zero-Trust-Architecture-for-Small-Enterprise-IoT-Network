ALTER TABLE mfa_security_audits
    DROP CONSTRAINT ck_mfa_security_audits_operation,
    ADD CONSTRAINT ck_mfa_security_audits_operation CHECK (
        operation IN (
            'ENROLLMENT_STARTED',
            'ENABLED',
            'DISABLED',
            'RECOVERY_CODE_USED',
            'RECOVERY_CODES_ROTATED'
        )
    );
