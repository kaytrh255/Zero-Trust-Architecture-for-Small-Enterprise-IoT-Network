ALTER TABLE mfa_login_challenges
    ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'LOGIN',
    ADD CONSTRAINT ck_mfa_login_challenges_purpose
        CHECK (purpose IN ('LOGIN', 'ENROLLMENT'));

CREATE INDEX ix_mfa_login_challenges_user_purpose
    ON mfa_login_challenges (user_id, purpose, expires_at DESC);
