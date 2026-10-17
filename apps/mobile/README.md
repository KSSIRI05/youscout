# YouScout — Frontend Flutter

Première itération issue de la maquette Figma, avec thème sombre, accents vert lime et police Inter embarquée. Interface en français et largeur adaptée aux petits écrans.

## Exécuter

SDK vérifié : Flutter 3.47.7 stable et Dart 3.13.5.

```sh
flutter pub get
flutter run -d web-server --web-port 7357
```

Ouvrir l’URL indiquée puis « Explorer la démo ». Android et iOS nécessitent les outils de plateforme ; les essais sur appareil restent à faire.

## Parcours disponibles

- Fil pour vous / abonnements, likes, commentaires et réponses, notation locale.
- Recherche de talents par nom et compétence, profils, abonnements et hashtags.
- Profil personnel modifiable, blocage et liste des utilisateurs bloqués.
- Sélection d’une vidéo depuis la galerie, caméra sur plateforme native, aperçu et métadonnées ; publication en mémoire. Une illustration permet aussi de tester sans fichier.
- Boîte de réception et messages locaux, signalements locaux explicites.

Aucune connexion serveur n’est réalisée. Les talents sont fictifs, la notation est provisoire, et les données sont réinitialisées au rechargement. Les signalements ne sont transmis à aucun modérateur. La vidéo sélectionnée reste locale : aucun stockage S3 ou transcodage. La limite de prévisualisation de 100 Mo est une protection locale, pas un quota backend validé.

## Vérifications

```sh
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
flutter build web --release
```

11 tests couvrent le store et les parcours principaux, dont la navigation à 320 × 640. La sélection caméra et la lecture sur appareils Android/iOS nécessitent une validation matérielle. La CI répète les contrôles sur Ubuntu avec le SDK fixé.

## Organisation

`lib/data` contient les modèles et l’interface `ScoutRepository`, avec une implémentation de démonstration. `lib/state` porte les mutations locales ; `lib/screens` les parcours ; `lib/widgets` les composants ; `lib/platform` adapte la source vidéo au navigateur et aux plateformes natives.

La prochaine étape est un dépôt API conforme aux contrats des services derrière la Gateway, puis OIDC/PKCE. Le frontend ne communiquera pas directement avec Kafka ou les bases des services.

Les illustrations Figma et la licence de police sont décrites dans [assets/README.md](assets/README.md). Aucun secret ni compte réel n’est nécessaire.
