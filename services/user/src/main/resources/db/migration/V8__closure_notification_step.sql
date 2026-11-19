-- U22 : Notification efface aussi les notifications, appareils et relations projetées d'un compte fermé.
ALTER TABLE account_closure_steps DROP CONSTRAINT account_closure_steps_step_check;
ALTER TABLE account_closure_steps ADD CONSTRAINT account_closure_steps_step_check
    CHECK (step IN ('SESSIONS', 'VIDEO', 'SOCIAL', 'RATING', 'CHAT', 'SKILL', 'NOTIFICATION', 'IDENTITY'));
-- Une fermeture en cours au moment de la migration passe aussi par Notification.
INSERT INTO account_closure_steps(subject, step)
SELECT subject, 'NOTIFICATION' FROM account_closures WHERE status = 'IN_PROGRESS'
ON CONFLICT DO NOTHING;
