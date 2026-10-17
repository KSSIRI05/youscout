# ADR-0002: Asynchronous media processing and CDN delivery

> Historique de la proposition initiale. Remplacé par [ADR-004](ADR-004-event-bus-kafka.md), [ADR-006](ADR-006-stockage-et-diffusion-video.md). Les choix ci-dessous sont conservés pour traçabilité, pas pour guider la nouvelle implémentation.

Statut : remplacé le 17 octobre 2026 ; ne constitue plus la cible actuelle.

## Context

Large video uploads and transcoding should not occupy synchronous application requests. Global viewing creates bandwidth and latency demands.

## Options

Proxy all uploads through the API; direct object-storage upload; synchronous or asynchronous transcoding; origin-only or CDN delivery.

## Decision and rationale

Authorize short-lived direct uploads to private object storage. Process videos through queued workers and expose completed media through controlled CDN access. Keep pending and failed states explicit. This separates interactive request latency from CPU-intensive processing and avoids routing video bytes through backend services.

## Consequences

Queue delivery can duplicate or fail; use idempotent jobs, bounded retries and dead-letter handling. Publication and deletion require reconciliation. CDN caches complicate moderation; combine invalidation with short-lived access URLs.

## Validation

Verify interrupted uploads, duplicate completion events, transcoding failure, unauthorized playback and disabled-content access.
