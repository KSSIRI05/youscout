# Contribuer à YouScout

Documenter en français les exigences, décisions et limites ; conserver des identifiants techniques stables. Utiliser des branches courtes et une revue par un autre membre. Relier les changements aux IDs U01-U22 / A01-A06 et aux ADRs pertinents.

La cible est microservices événementiels, Flutter, Kafka, données propriétaires, S3/CDN et Kubernetes. Spring Boot constitue le backend de référence, React le back-office. Une alternative majeure nécessite un ADR avant migration ; ne pas maintenir deux stacks concurrentes sans justification.

Chaque service possède migrations et données ; ne pas réutiliser les entités persistantes d'un autre service comme bibliothèque partagée. Partager les contrats et conventions, pas les tables. Versionner OpenAPI et schémas d'événements ; privilégier les changements compatibles.

Contrôler tout code assisté par IA. Tester les droits, transactions/outbox, retries, événements tardifs et suppressions ; compléter par tests Flutter et admin. Ne pas prétendre qu'un test passe avant exécution. Les commandes exactes seront ajoutées lorsque les projets existent.

Secrets, jetons, données privées, médias utilisateur et états Terraform ne vont pas dans Git. Les exemples d'environnement contiennent seulement des noms et valeurs factices. Les ADRs acceptés expriment un choix de conception, pas une preuve de fonctionnement. Une décision remplacée est conservée avec renvoi vers sa remplaçante.
