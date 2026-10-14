# MVP and implementation roadmap

All stages are proposed. The complete assignment scope remains in [requirements](REQUIREMENTS.md).

## Stage 1: architecture foundation

- Review quality priorities and accept or revise ADRs.
- Scaffold mobile, admin, backend services and local Docker Compose.
- Establish authenticated API contracts, migrations, health checks and CI.
- Define media limits, ratings, blocking semantics and account closure rules.

## Stage 2: demonstration MVP

- U01-U05, U07-U11 and U14: accounts, third-party sign-in, profiles, gallery publishing, descriptions, hashtags, skills, feed, comments and likes.
- U16-U17 and A01-A05: reporting, moderation views, disabling and skill administration.
- Implement server-side ownership checks, reliable media jobs and retry handling.
- Deliver one vertical flow: sign in, upload a football video, process it, view it in the feed, interact and moderate.

## Stage 3: remaining requirements

- In-app recording, comment replies and comment deletion rules.
- Skill ratings and edits, user/hashtag following and unfollowing.
- Blocking, instant chat and account closure.
- Admin dashboard and refinements to discovery.

## Stage 4: deploy and present

- Deploy staging, test the critical user flow on mobile and verify admin authorization.
- Perform representative load, processing failure, duplicate-event and recovery checks.
- Document actual launch commands, environment variables and limitations.
- Prepare the architecture presentation and live demonstration.
- Publish source and docs to GitHub and provide evaluator access if private.

## Proposed four-person ownership

| Owner | Primary responsibility |
| --- | --- |
| Member 1 | Identity, profiles and security integration |
| Member 2 | Social features and moderation |
| Member 3 | Media processing and infrastructure |
| Member 4 | Mobile and admin interfaces |

Share reviews, integration, deployment and presentation work. Record AI assistance and validate generated code through review and meaningful tests.

## Completion checklist

- [ ] All U01-U22 and A01-A06 requirements implemented and verified.
- [ ] ADRs reviewed and reflect the implemented architecture.
- [ ] Mobile client demonstrated on supported platforms.
- [ ] Application deployed and recovery procedure checked.
- [ ] README launch instructions verified from a clean checkout.
- [ ] Complete code and documentation accessible to evaluators on GitHub.
