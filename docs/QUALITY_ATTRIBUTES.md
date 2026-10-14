# Architecture characteristics

## Explicit drivers

| Characteristic | Evidence from the brief |
| --- | --- |
| Scalability | Thousands of launch users, potentially millions later. |
| Geographic reach | Staged expansion across several regions and then worldwide. |
| Cross-platform delivery | Mobile frontend must use a cross-platform technology. |
| Deployability | Application must be deployed in a suitable environment. |

The unconstrained budget is a business constraint, not a quality attribute.

## Implicit characteristics

Availability follows from a worldwide social product. Responsiveness follows from video feeds and instant chat. Security and privacy follow from identity, personal profiles, messages and administration. Modifiability supports evolving discovery and moderation features. Reliability and recoverability protect uploads, publications and messages. Observability is necessary to operate distributed components. These are inferred needs, not numeric guarantees in the assignment.

## Proposed top priorities

1. Scalability: isolate media processing and delivery from interactive requests.
2. Security and privacy: enforce authorization, least privilege and protected data access.
3. Availability: keep feed reads operational when nonessential background work is delayed.
4. Responsiveness: serve media through a CDN and process videos asynchronously.
5. Modifiability: keep ownership and contracts clear between domains.

## Proposed validation targets

For an initial controlled deployment, evaluate 99.9% monthly API availability, p95 ordinary API response time below 500 ms (excluding uploads and media transfer), and p95 chat delivery below 1 second for connected users in one region. These are candidate targets, not measured results or assignment requirements. Set concurrent-user and throughput loads after establishing the deployment budget and workload model. Test worker failure, duplicate event delivery and interrupted uploads. Revisit targets before global expansion.
