# ADR 0001: Native monorepo and contract ownership

- Status: Accepted
- Date: 2026-06-20

## Context

SwiftShare requires native macOS and Android lifecycle, storage, cryptographic,
and networking behavior. The two applications must still agree on protocol and
Transfer Session semantics without coupling their runtime implementations.

## Decision

- Keep the Swift and Kotlin runtime implementations in separate native projects.
- Share only schemas, specifications, capability definitions, state-machine
  descriptions, and golden fixtures under `contracts/`.
- Give each application a domain boundary that does not import UI, platform
  storage, discovery, transport, account, or backend implementations.
- Make Transfer Session the acceptance seam. Identity storage, the Paired Peer
  directory, authenticated transport, clock, and destination storage are injected
  collaborators at that boundary.
- Bootstrap semantic agreement with JSON in SS-001. Introduce Protobuf schemas and
  binary vectors in SS-002 when the first wire records exist.
- Treat root `make` commands as the public local build/test interface. Aggregate
  commands fail when either native toolchain is unavailable.

## Consequences

- Native code can follow platform lifecycle and storage rules without a KMP or
  cross-platform UI dependency.
- Contract changes require independent Swift and Kotlin verification.
- Some domain types are intentionally duplicated, while their observable meaning
  is held stable by shared fixtures.
- A future account layer may provide adapters but cannot become a dependency of
  LAN Mode or the Transfer Session domain.

