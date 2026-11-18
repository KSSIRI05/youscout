# Chat Service

Messagerie instantanée entre deux comptes (U21), conformément à [ADR-008](../../docs/adr/ADR-008-chat-websocket.md). Les requêtes REST nécessitent un JWT Keycloak valide avec le rôle `user` ou `admin` ; l'expéditeur vient exclusivement du claim `sub`.

- `GET /v1/conversations` : conversations de l'appelant, la plus récente d'abord, avec le dernier message et les séquences livrées.
- `GET /v1/conversations/{peer}/messages` : page de la conversation par `seq` croissant ; `afterSeq` reprend après une reconnexion, `beforeSeq` remonte l'historique.
- `POST /v1/conversations/{peer}/messages` avec `{"clientMessageId", "body"}` : envoi de secours sans WebSocket (201, ou 200 pour un renvoi).
- `POST /v1/conversations/{peer}/delivered` avec `{"seq"}` : accusé de réception jusqu'à `seq`.
- `GET /v1/chat/ws` : connexion WebSocket ; trames et codes de fermeture dans le contrat.

Le contrat complet est [chat.yaml](../../contracts/openapi/chat.yaml).

## Règles appliquées

- **Enregistré avant l'accusé** : `accepted` (WebSocket) ou 201 (REST) n'est renvoyé qu'après l'écriture dans MongoDB. « Envoyé » (accepté par le serveur) et « Reçu » (accusé du destinataire) sont deux états distincts ; « Lu » n'est pas encore géré.
- **Séquence sans trou** : chaque conversation numérote ses messages 1, 2, 3… L'incrément du compteur et l'insertion du message partagent une transaction MongoDB (replica set requis), rejouée en cas de conflit d'écriture.
- **Déduplication** : l'expéditeur choisit `clientMessageId` ; un index unique `(senderSubject, clientMessageId)` fait qu'un renvoi après coupure renvoie le message d'origine, sans nouvelle livraison. Réutiliser l'identifiant pour un autre contenu donne 409.
- **Destinataire et blocages** : User confirme que le destinataire a un profil actif (404 sinon, y compris pour un compte désactivé) et qu'aucun blocage n'existe dans un sens ou dans l'autre (403). Ces appels transmettent le jeton de l'expéditeur, avec un délai de 3 secondes ; si User est indisponible, l'envoi est refusé (503). Un renvoi d'un message déjà accepté reste possible après un blocage, l'historique existant reste lisible.
- **Authentification WebSocket** : le navigateur ne peut pas joindre d'en-tête `Authorization` à la poignée de main ; la Gateway la laisse donc passer et Chat exige une trame `auth` dans les 10 secondes. La connexion est fermée (4401) à l'expiration du jeton si le client n'en a pas envoyé un nouveau ; un rôle absent ou un changement de compte ferme en 4403. Les origines web admises sont celles de la Gateway (`CHAT_ALLOWED_ORIGINS`) ; l'application mobile n'envoie pas d'en-tête Origin.
- **Plusieurs instances** : chaque instance publie les livraisons sur le canal Redis `youscout.chat.v1` et les remet à ses propres connexions. Sans Redis, Chat reste prêt : les messages sont enregistrés et livrés aux connexions de la même instance, les autres clients les récupèrent par l'historique à leur reconnexion.
- **Confidentialité** : les journaux ne contiennent jamais le contenu des messages.

Le service possède sa base MongoDB `youscout_chat` et ne lit pas la base User. Les événements `MessageAccepted` et `MessageDelivered` ne sont pas encore émis : aucun consommateur (notifications push, analytics) n'existe pour l'instant. Les anciens messages REST de Social (`direct_messages`) ne sont pas migrés ; la table reste en place mais n'est plus utilisée.

## Tests

`ChatIntegrationTest` utilise MongoDB en replica set et Redis (Testcontainers) avec une doublure HTTP de User : séquence et renvoi idempotent, curseurs, accusés de réception, refus (destinataire inconnu, blocages, User indisponible), 24 envois simultanés sans trou ni doublon, livraison WebSocket, authentification et expiration du jeton, origine refusée, et livraison entre deux instances par Redis. `scripts/chat_smoke.py` vérifie le câblage réel Gateway → Chat → User, en REST et en WebSocket.
