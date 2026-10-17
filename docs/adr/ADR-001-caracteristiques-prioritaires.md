# ADR-001 : Caractéristiques prioritaires

Statut : Retenu pour la conception, décision du porteur le 17 octobre 2026. Validation technique à réaliser.

## Contexte

Le sujet impose une croissance potentielle importante, une audience internationale et des usages vidéo/temps réel. Les objectifs chiffrés ne sont pas fournis.

## Alternatives

Privilégier coût/simplicité ; privilégier croissance et isolation ; optimiser toutes les qualités sans ordre.

## Décision et justification

Retenir scalabilité/élasticité, disponibilité/tolérance aux pannes, performance, évolutivité/déployabilité puis sécurité/protection des données. La sécurité reste obligatoire dès le socle. Les hypothèses sont séparées des exigences explicites.

## Conséquences et limites

Les autres qualités restent nécessaires. Les choix distribués ajoutent du travail opérationnel, même avec budget libre. Les cibles de latence/disponibilité sont proposées et ne deviennent garanties qu’après validation.

## Validation et réexamen

Mesurer les scénarios de QUALITY_ATTRIBUTES avec charge et région explicites ; revoir les priorités si les usages ou moyens changent.

Voir [architecture](../ARCHITECTURE.md), [outils et sources officielles](../OUTILS_ET_ALTERNATIVES.md) et [roadmap](../ROADMAP.md).
