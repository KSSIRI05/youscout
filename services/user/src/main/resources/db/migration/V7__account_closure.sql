-- U22 : fermeture demandée par le joueur. CLOSING bloque les actions et attend les confirmations des services ;
-- CLOSED ne garde qu'une ligne anonymisée (sujet, dates), pour qu'un rejeu ne ressuscite pas le compte.
ALTER TABLE user_profiles DROP CONSTRAINT user_profiles_account_status_check;
ALTER TABLE user_profiles ADD CONSTRAINT user_profiles_account_status_check
    CHECK (account_status IN ('ACTIVE', 'DEACTIVATED', 'CLOSING', 'CLOSED'));

ALTER TABLE account_audit DROP CONSTRAINT account_audit_action_check;
ALTER TABLE account_audit ALTER COLUMN action TYPE VARCHAR(20);
ALTER TABLE account_audit ADD CONSTRAINT account_audit_action_check
    CHECK (action IN ('DEACTIVATE', 'REACTIVATE', 'CLOSE_REQUESTED', 'CLOSED'));

CREATE TABLE account_closures (
    subject VARCHAR(255) PRIMARY KEY REFERENCES user_profiles(subject),
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- Les effacements commencent quand les jetons d'accès émis avant la fermeture ont expiré.
    purge_after TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX account_closures_due_idx ON account_closures (next_attempt_at) WHERE status = 'IN_PROGRESS';

-- Journal de traitement : une ligne par étape, avec le nombre d'essais et la dernière erreur (sans détail sensible).
CREATE TABLE account_closure_steps (
    subject VARCHAR(255) NOT NULL REFERENCES account_closures(subject),
    step VARCHAR(20) NOT NULL CHECK (step IN ('SESSIONS', 'VIDEO', 'SOCIAL', 'RATING', 'CHAT', 'SKILL', 'IDENTITY')),
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error VARCHAR(200),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (subject, step),
    CHECK ((status = 'DONE') = (completed_at IS NOT NULL))
);
