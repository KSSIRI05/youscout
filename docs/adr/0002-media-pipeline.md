# ADR-0002: Asynchronous media processing and CDN delivery

Status: Proposed

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
