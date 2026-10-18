# Projet et cadre

Ce document présente la vision produit de YouScout, son périmètre fonctionnel et les choix de conception de l'équipe.

YouScout vise la visibilité et l'égalité des chances des talents du football, la connexion entre fans, joueurs, entraîneurs et scouts, et la découverte de talents par les recruteurs. Les utilisateurs partagent des vidéos de leurs compétences.

## Objectifs et contraintes du produit

- Milliers d'utilisateurs au lancement, croissance possible vers des millions.
- Maroc et huit pays européens en premier : Angleterre, France, Espagne, Portugal, Allemagne, Pays-Bas, Belgique et Italie. Puis MENA, Afrique subsaharienne, Amérique latine et monde.
- Budget, disponibilité de l'équipe et capacité opérationnelle à valider avant chaque phase de déploiement.
- Choix d'architecture documentés par des ADRs, diagrammes et comparaisons d'alternatives.
- Application mobile cross-plateforme, backend vérifié et déploiement reproductible.
- Code source versionné, tests automatisés et instructions de lancement maintenues avec les fonctionnalités.

## Choix retenus pour la conception

Microservices orientés événements, Flutter, Kafka, bases propriétaires par service, S3/CDN et Kubernetes. Les choix de stack et leurs alternatives sont précisés dans [le guide des outils](OUTILS_ET_ALTERNATIVES.md). Les documents distinguent la cible des composants déjà implémentés et testés.

## Questions encore ouvertes

Échelle de notation ; durée/taille des vidéos ; conservation des contenus et messages ; politique d'âge ; vérification éventuelle des scouts ; langues de lancement ; règles de classement et indicateurs d'équité ; capacité et concurrence attendues ; choix final de région et dimensionnement.

Le rôle « scout », les notifications push, la recherche avancée et la personnalisation du feed sont des extensions produit à préciser et prioriser. Le multi-région est une évolution technique à évaluer selon les besoins du lancement international.
