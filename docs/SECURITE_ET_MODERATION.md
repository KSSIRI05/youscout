# Sécurité, retrait et cycle de vie

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

User orchestre un workflow suivi : bloquer les nouvelles actions, révoquer sessions/accès auprès d'Auth, demander les suppressions ou anonymisations aux propriétaires, suivre les confirmations, purger les objets et projections selon la politique définie, puis clôturer. Échecs et retries sont visibles dans un journal de traitement. Les sauvegardes et journaux ont une rétention définie ; les messages déjà reçus par autrui demandent une décision produit explicite. Ne pas promettre une suppression immédiate et totale sans politique ni mécanisme.

## Mesures transversales

TLS, identités de service, permissions Kafka par topic, bases privées et identifiants séparés, secrets hors Git, quotas d'upload, inspection du vrai format média, worker FFmpeg isolé sans privilèges avec limites CPU/mémoire/temps. Les logs excluent mots de passe, jetons et corps des messages privés. Sauvegardes et restauration sont testées.

La conception prévoit minimisation, rétention et effacement ; elle ne certifie aucune conformité juridique. Les règles applicables aux pays et publics ciblés devront être examinées avant lancement.

Référence technique : [Keycloak et OIDC](https://www.keycloak.org/securing-apps/oidc-layers).
