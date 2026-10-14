# ADR-0003: Managed container deployment

Status: Proposed

## Context

The assignment requires deployment, but does not mandate a provider or Kubernetes. Four team members must operate a demonstrable distributed foundation.

## Options

Single virtual machine; managed container platform; managed Kubernetes; functions.

## Decision and rationale

Start with Docker Compose locally and managed containers for the deployed environment. Use managed databases, storage and broker where practical. Containers preserve deployable service boundaries; managed hosting reduces maintenance. Choose region and provider after an actual comparison.

## Consequences

Provider integrations and costs require review. Single-region hosting has regional outage risk. Add backups, health checks and restoration drills; expand resilience and regional placement when validated demand requires it.

## Validation

Verify a clean staging deployment, migrations, rollback and database restoration. Revisit Kubernetes when platform constraints justify its overhead.
