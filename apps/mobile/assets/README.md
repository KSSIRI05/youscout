# Ressources visuelles

L'identité « Terrain de détection » est dessinée en Flutter, sans image : la pelouse tondue, les lignes de craie, le plot de notation et le poste du joueur sont des `CustomPainter` dans `lib/widgets/terrain.dart`. Les couleurs (clair et sombre) sont dans `lib/core/theme.dart`.

## Polices

Deux familles, embarquées en instances statiques depuis Google Fonts. Flutter n'applique pas `fontWeight` à l'axe d'une police variable ; un fichier par graisse garantit la graisse demandée.

| Famille Flutter | Fichiers | Rôle | Licence |
| --- | --- | --- | --- |
| `BigShoulders` | BigShouldersDisplay-SemiBold (600), -ExtraBold (800) | Noms de joueurs, titres d'écran, moyennes | SIL OFL 1.1, `fonts/OFL-BigShouldersDisplay.txt` |
| `ReadexPro` | ReadexPro-Light (300), -Regular (400), -Medium (500), -SemiBold (600) | Tout le reste du texte | SIL OFL 1.1, `fonts/OFL-ReadexPro.txt` |

Readex Pro est dessinée pour le latin et l'arabe. Les fichiers embarqués proviennent de l'API Google Fonts : avant d'ajouter une interface arabe, vérifier qu'ils contiennent les glyphes arabes, sinon les remplacer par la version complète.

Les polices fonctionnent sans téléchargement à l'exécution.
