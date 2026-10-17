# YouScout Copilot instructions

## Repository shape

This repository is a mixed backend + mobile workspace, not a single app. The important split is:

- `apps/mobile`: Flutter frontend demo for the social talent app. It is currently a local, in-memory prototype with Flutter web as the primary validation target.
- `services/gateway`: Spring Cloud Gateway (WebFlux) that validates JWTs, applies CORS and local routing rules, and forwards traffic to downstream services.
- `services/user`: Spring MVC service with JDBC + Flyway that owns the persistent user profile data in PostgreSQL.
- `infra/compose`: local Docker Compose stack for PostgreSQL, Keycloak, and the backend services.
- `scripts`: automation for local environment setup, Keycloak configuration, and smoke checks.
- `contracts/openapi`: versioned API contract(s), especially the user-foundation API.
- `docs`: product/architecture decisions and implementation notes; treat those as the source of truth for scope, target architecture, and constraints.

The codebase is a partial foundation for the target architecture described in the docs: event-driven microservices behind an API gateway, with Flutter on mobile and Keycloak for auth. Do not assume the full target architecture is already implemented; the current repo is a working MVP foundation with a real local backend stack and a frontend demo.

## Build, test, and lint commands

### Backend (Java / Spring Boot)

Prerequisites: Java 21, Docker, Python 3.

Full verification from the repo root:

```sh
./mvnw -B -ntp verify
```

PowerShell:

```powershell
.\mvnw.cmd -B -ntp verify
```

Run a single backend test class in a module:

```sh
./mvnw -pl services/gateway -Dtest=SecurityIntegrationTest test
./mvnw -pl services/user -Dtest=SecurityIntegrationTest test
```

Use `-Dtest=ClassName` to target a single JUnit class; use the module path when you need to keep the run scoped to one service.

Local backend stack setup:

```sh
python infra/compose/init-env.py
docker compose -f infra/compose/compose.yml up -d --build --wait --wait-timeout 300
python scripts/apply-keycloak.py
python scripts/smoke-backend.py
python scripts/smoke-identity.py
```

If you need to reset or inspect the local stack:

```sh
docker compose -f infra/compose/compose.yml ps
docker compose -f infra/compose/compose.yml logs --tail 100 gateway user
docker compose -f infra/compose/compose.yml down
```

### Mobile frontend (Flutter)

This repo pins Flutter 3.47.7 and Dart 3.13.5.

```sh
cd apps/mobile
flutter pub get
flutter run -d web-server --web-port 7357
```

Validation commands:

```sh
cd apps/mobile
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
flutter build web --release
```

Target a single Flutter test file:

```sh
cd apps/mobile
flutter test test/frontend_test.dart
flutter test test/scout_store_test.dart
```

When a failing Flutter test needs a single case, use `--plain-name` with the test name instead of broad suite runs.

## High-level architecture

The project is organized around a gateway-first architecture and a demo-first frontend.

1. Client-facing experience
   - `apps/mobile` exposes the user experience and local demo flows: feed, discovery, profile editing, blocked users, local messaging, and upload preview.
   - The app is intentionally local-only today; it does not yet perform real backend auth or service calls.

2. API boundary
   - `services/gateway` is the first backend entry point. It validates JWTs, applies local security rules, and routes requests such as `/v1/users/**` to downstream services.
   - Gateway is not the persistence layer; it is an edge component.

3. Business service ownership
   - `services/user` owns user profile persistence and related logic.
   - Each service is expected to own its own database schema and migrations; avoid sharing `@Entity` classes or database tables between services.

4. Identity and local environment
   - Keycloak provides the auth realm and roles (`user`, `admin`) used by local validation.
   - The local stack in `infra/compose` is used for Docker-backed verification, not for production configuration.
   - `infra/compose/init-env.py` creates a local `.env` file; secrets and environment-specific values stay out of git.

5. Contract-driven evolution
   - `contracts/openapi` and the docs under `docs/` describe the intended API and event-driven design.
   - When changing contracts, include the corresponding API/documentation and check whether the consumer and producer sides still line up.

## Key conventions in this codebase

- Service boundaries are real: `gateway` handles routing and auth checks, while `user` handles persistent profile data. Do not mix persistence logic into the gateway layer.
- Keycloak JWT validation is the trust boundary. Do not treat client-supplied role or identity headers as authorization sources.
- Use the repo’s local Docker-based environment for backend integration work rather than ad hoc local services. This repo expects the compose stack and smoke scripts to be part of the verification path.
- Keep secrets and local env values in `infra/compose/.env` and other generated local artifacts, not in versioned files.
- Preserve migration discipline: maintain versioned Flyway migrations, and avoid rewriting already-applied migrations.
- Treat the docs as architecture-level guidance. `docs/ARCHITECTURE.md`, `docs/BACKEND.md`, and the ADR set explain the intended system boundaries and constraints more accurately than a single service file.
- Mobile work should stay in the existing demo pattern unless the task explicitly calls for production backend integration.

## Working style for this repository

- When changing backend behavior, review the gateway + user service together and verify whether the API contract is affected.
- When changing the frontend, prefer the existing Flutter demo architecture (`lib/data`, `lib/state`, `lib/screens`, `lib/widgets`, `lib/platform`) instead of introducing ad hoc app-wide state.
- If a change affects auth, API contracts, or persistence, validate it with the Docker-backed backend flow and not only a unit test.

## Relevant project documents

- `README.md`: repo overview, local startup flow, and core architecture description.
- `CONTRIBUTING.md`: project rules and constraints, especially around architecture, security checks, and migration discipline.
- `docs/BACKEND.md`: local backend stack, Keycloak, endpoints, and validation workflow.
- `docs/ARCHITECTURE.md`: target microservice/event-driven design.
- `apps/mobile/README.md`: frontend demo scope and validation steps.
