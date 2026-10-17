CREATE TABLE skill_audit (
    id UUID PRIMARY KEY,
    skill_id UUID NOT NULL REFERENCES skills(id),
    actor_subject VARCHAR(255) NOT NULL,
    action VARCHAR(10) NOT NULL CHECK (action IN ('CREATE','UPDATE')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX skill_audit_by_skill ON skill_audit(skill_id,occurred_at);
