# Proposed deployment

Status: planning only; no infrastructure has been provisioned.

Use Docker Compose locally. For the first deployed demonstration, propose managed container hosting, managed PostgreSQL, a managed broker, private object storage and CDN delivery in one region. Select a provider and region after comparing cost, supported identity/storage integrations and latency for initial markets. Do not infer a provider from the assignment.

Managed containers reduce operational work for four students while supporting separate service and worker scaling. Kubernetes is an option when orchestration requirements justify its operating overhead; it is not necessary solely because the assignment mentions it. See [ADR-0003](adr/0003-deployment.md).

## Deployment requirements

- Build versioned container images through CI and deploy to staging before production.
- Store secrets outside Git; document variable names in a future `.env.example`.
- Terminate TLS, authenticate APIs and restrict databases/broker to private networking.
- Configure readiness checks, resource limits and controlled rollouts.
- Use presigned uploads, private media storage and expiring authorized playback URLs.
- Restrict processing workers' privileges and validate uploaded file types and sizes.
- Collect latency, error rate, queue depth, processing failure and database health metrics.
- Back up databases, define object retention and test restoration.
- Coordinate content disabling with CDN invalidation and access expiry.
- Review personal-data, account deletion and regional requirements before public launch.

## Before this document becomes a runbook

Record the actual provider, region, resources, domains, identity configuration, deployment/migration commands, rollback steps, backup schedule, recovery targets and verified smoke tests. No deployment command is included until implementation exists.
