-- A04 : un administrateur peut désactiver un compte. Le profil est conservé pour permettre la réactivation.
ALTER TABLE user_profiles
    ADD COLUMN account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (account_status IN ('ACTIVE', 'DEACTIVATED')),
    ADD COLUMN status_changed_at TIMESTAMPTZ,
    ADD COLUMN status_changed_by VARCHAR(255),
    ADD COLUMN status_note VARCHAR(500);

CREATE TABLE account_audit (
    id UUID PRIMARY KEY,
    subject VARCHAR(255) NOT NULL REFERENCES user_profiles(subject),
    actor_subject VARCHAR(255) NOT NULL,
    action VARCHAR(12) NOT NULL CHECK (action IN ('DEACTIVATE', 'REACTIVATE')),
    note VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX account_audit_subject_idx ON account_audit (subject, occurred_at);
