# Sécurité, retrait et cycle de vie

Un premier contrôle des rôles est implémenté dans la Gateway et User. Voir [l’état de l’identité et des autorisations](IDENTITE_ET_AUTORISATIONS.md). Les mécanismes de modération, suspension et fermeture décrits ci-dessous restent des cibles.

## Identité et autorisation

Keycloak fournit OIDC et la fédération Google/Facebook. Flutter utilise le navigateur système et Authorization Code avec PKCE, sans secret client embarqué. Les services valident signature, issuer, audience et expiration du jeton ; les rôles `user` et `admin` sont distincts. Un rôle scout éventuel nécessite une règle produit validée. Un rôle ne remplace pas le contrôle de propriété.

Les endpoints administratifs exigent un rôle admin et journalisent l'action. La Gateway ne constitue pas la seule défense : services et entrée WebSocket appliquent leurs propres contrôles. Les jetons courts limitent la fenêtre après suspension ; une vérification d'état/revocation est nécessaire pour les actions sensibles et les connexions persistantes.

## Désactivation d'une ressource

1. Moderation enregistre la décision et une commande de désactivation dans son outbox.
2. Le propriétaire applique le statut local dans une transaction, puis confirme par événement. L'interface affiche `PENDING` jusqu'à confirmation et échec éventuel, sans prétendre à une désactivation globale instantanée.
3. Feed/recherche/analytics traitent la confirmation. Les lecteurs et services d'interaction vérifient la visibilité nécessaire, avec repli sûr si l'état d'autorisation n'est pas disponible.
4. Video refuse de nouvelles signatures média ; invalide le CDN et retire les rendus selon la politique retenue. Une URL déjà signée reste potentiellement valable jusqu'à expiration : fixer et tester cette durée. Si une révocation immédiate est exigée, ajouter un contrôle d'accès en bordure ou un mécanisme équivalent, pas seulement une invalidation de cache.

Au MVP technique sans Moderation, une vidéo peut encore être supprimée par son auteur et un skill désactivé par admin. Ce socle n'est pas un lancement public complet : U16/U17 et A04 restent à réaliser avant une diffusion publique prévue par ce projet.

## Blocage et chat

User persiste le blocage ; les projections sont mises à jour par événements versionnés. Politique proposée à confirmer : empêcher les échanges et masquer mutuellement les contenus. Chat vérifie le droit d'émettre, refuse les nouveaux messages bloqués et actualise les connexions actives. Un cache obsolète ne suffit pas à une garantie stricte : prévoir vérification propriétaire ou projection d'autorisation avec politique de refus en cas d'incertitude.

## Fermeture de compte

Implémentée (U22). Le joueur saisit `FERMER` après l'explication des conséquences ; User orchestre ensuite un workflow suivi :

1. **Immédiatement** : le compte passe `CLOSING` (événement `UserAccountChanged`, Feed masque ses vidéos), User refuse ses actions même avec un jeton encore valide (403, donc aussi Social, Rating et Chat via `feed-relations`), son profil devient introuvable, Keycloak désactive le compte et ferme ses sessions.
2. **Après expiration des jetons déjà émis** (6 min ; 5 min de durée de vie plus une marge), User appelle `DELETE /internal/accounts/{subject}` de chaque service avec son compte de service (rôle realm `account-closure`, route absente de la Gateway) : Video supprime toutes ses vidéos (`VideoDeleted`, retrait du fil et nettoyage S3 par le worker), Social supprime ses likes et vide puis masque ses commentaires, Rating supprime ses notes, Chat efface le texte de ses messages, Skill retire ses abonnements aux hashtags.
3. **Ensuite** : le compte Keycloak est supprimé (l'e-mail redevient libre), suivis et blocages sont retirés avec leurs événements, le profil devient « Compte fermé » `CLOSED`.

Chaque étape est idempotente, journalisée (essais, dernière erreur sans détail distant) et rejouée avec un délai croissant ; un service indisponible n'empêche pas les autres d'avancer. Les administrateurs lisent le journal dans la console ; un compte en fermeture ne peut être ni désactivé ni réactivé.

Décisions retenues, à confirmer par le produit : les messages déjà reçus restent à leur place chez l'interlocuteur mais leur texte est effacé (« Message supprimé ») ; les signalements déposés par le compte restent dans la file de modération, rattachés à son seul identifiant ; la ligne `CLOSED`, l'audit et le journal de fermeture sont conservés sans nom ni biographie pour qu'un rejeu ne ressuscite pas le compte ; les événements Kafka déjà publiés suivent la rétention de leur topic. Les sauvegardes ne sont pas encore traitées : leur rétention reste à définir avant lancement. Pas de réauthentification récente exigée pour l'instant.

## Mesures transversales

TLS, identités de service, permissions Kafka par topic, bases privées et identifiants séparés, secrets hors Git, quotas d'upload, inspection du vrai format média, worker FFmpeg isolé sans privilèges avec limites CPU/mémoire/temps. Les logs excluent mots de passe, jetons et corps des messages privés. Sauvegardes et restauration sont testées.

La conception prévoit minimisation, rétention et effacement ; elle ne certifie aucune conformité juridique. Les règles applicables aux pays et publics ciblés devront être examinées avant lancement.

Référence technique : [Keycloak et OIDC](https://www.keycloak.org/securing-apps/oidc-layers).
