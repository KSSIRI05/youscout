# Projet et cadre

Source métier : `Projet ALD (25-26) (1).pdf`, INPT, Architectures Logicielles Distribuées, ASEDS, Prof. Driss ALLAKI, année 2025/2026, pages 1 à 3. Ce document distingue les exigences du sujet des choix de conception de l'équipe.

YouScout vise la visibilité et l'égalité des chances des talents du football, la connexion entre fans, joueurs, entraîneurs et scouts, et la découverte de talents par les recruteurs. Les utilisateurs partagent des vidéos de leurs compétences.

## Contraintes du sujet

- Milliers d'utilisateurs au lancement, croissance possible vers des millions.
- Maroc et huit pays européens en premier : Angleterre, France, Espagne, Portugal, Allemagne, Pays-Bas, Belgique et Italie. Puis MENA, Afrique subsaharienne, Amérique latine et monde.
- Budget non contraint comme orientation stratégique ; cela ne supprime pas les limites de temps d'une équipe de quatre.
- Partie A, 14 points : caractéristiques explicites/implicites et priorités, comparaison de styles, diagramme et justification de chaque décision par ADR.
- Partie B, 6 points : socle implémenté avec assistance IA, mobile cross-plateforme et déploiement justifié.
- Groupe de quatre, présentation et démonstration, dépôt GitHub public ou privé partagé avec code complet, ADRs et README de lancement.

## Choix retenus pour la conception

Microservices orientés événements, Flutter, Kafka, bases propriétaires par service, S3/CDN et Kubernetes. Les options de stack non imposées dans le texte initial sont précisées dans [le guide des outils](OUTILS_ET_ALTERNATIVES.md). Les documents décrivent une cible, pas une réalisation déjà testée.

## Questions encore ouvertes

Échelle de notation ; durée/taille des vidéos ; conservation des contenus et messages ; politique d'âge ; vérification éventuelle des scouts ; langues de lancement ; règles de classement et indicateurs d'équité ; capacité et concurrence attendues ; choix final de région et dimensionnement.

Le rôle « scout », les notifications push, la recherche avancée et la personnalisation du feed sont des choix ou extensions produit, non des exigences détaillées imposées par le sujet. Le multi-région est une évolution technique, non une conséquence obligatoire du lancement international.
