# ADR-011 : Stack de référence

Statut : recommandé, à valider par prototype et compétences de l'équipe. L'architecture distribuée est retenue ; le texte fourni laissait plusieurs frameworks ouverts.

## Contexte et alternatives

Il faut concrétiser Flutter, les services métier et le back-office sans multiplier les stacks. Alternatives backend : Spring Boot, FastAPI ou NestJS ; Gateway : Spring Cloud Gateway, Kong ou NGINX ; admin : React ou Angular.

## Recommandation et justification

Flutter/Dart pour le mobile retenu ; Java/Spring Boot pour les services ; Spring Cloud Gateway ; React avec TypeScript pour l'admin ; Keycloak pour Auth. Spring apporte une intégration cohérente Kafka, sécurité et données. Utiliser les versions stables mutuellement compatibles et les verrouiller au démarrage, sans affirmer une version installée aujourd'hui.

## Conséquences

Java, Dart et TypeScript ont chacun leur rôle. FastAPI devient préférable si la maîtrise Python de l'équipe réduit fortement le risque de livraison ; sa sélection changerait cet ADR, pas le principe microservices/Kafka. Comparaison détaillée et sources dans [le guide](../OUTILS_ET_ALTERNATIVES.md).

## Validation

Un prototype OIDC, un service transactionnel Kafka/outbox et un upload/lecteur Flutter sur appareils cibles. Ne pas annoncer de benchmark ou gain de productivité non mesuré.
