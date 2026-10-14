# ADR-0001: Small domain-oriented service architecture

Status: Proposed

## Context

Video delivery, instant chat and growth to potentially millions of users imply differing scaling and failure profiles. The four-person team also needs a manageable implementation.

## Options

Layered monolith; modular monolith with workers; small microservices; predominantly serverless.

## Decision and rationale

Use identity/profile, social/moderation, media and chat boundaries with a separate media worker. This isolates costly media work and real-time traffic while keeping related social transactions together. Independent ownership and deployment suit the growth driver; the added complexity must be justified through the demonstration.

## Consequences

Benefits: targeted scaling and failure isolation. Costs: distributed consistency, contract management and operating several components. Mitigate with few services, an outbox, idempotent consumers and observability. Prefer a modular monolith with workers if the team cannot sustain the operational scope.

## Validation

Demonstrate independent worker scaling and continued feed reads during worker failure. Revisit boundaries using measured bottlenecks.
