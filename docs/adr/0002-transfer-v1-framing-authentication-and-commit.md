# ADR 0002: Transfer v1 framing, authentication, and Commit

- Status: Accepted
- Date: 2026-06-21

## Context

SS-002 is the first real wire path between the native clients. It must stream a
large file without whole-file buffering, authenticate a pre-provisioned development
Peer, and avoid publishing a partial Android destination.

## Decision

- Use TLS 1.3 over TCP and authenticate both configured development identities
  before parsing application records.
- Keep private P-256 identity keys in Keychain and Android Keystore. Development
  configuration exchanges only public certificates or SPKI pins and endpoint
  metadata.
- Use the fixed bounded framing and Protobuf control messages specified by
  `contracts/transfer-v1.md`. File bytes use raw Chunk records.
- Split Transfer Session orchestration into outbound and inbound roles beneath a
  shared event, progress, error, and terminal-result vocabulary.
- On Android, reserve a candidate name by immediately inserting an app-owned
  `IS_PENDING` MediaStore row under a single-writer SwiftShare coordinator. The
  inserted URI is the reservation and staging identity. The coordinator protects
  concurrent SwiftShare allocations; no cross-application atomic uniqueness claim
  is made.
- Publish the reserved URI only after Chunk and whole-file verification. Reject,
  cancellation, timeout, disconnect, permission loss, storage failure, or integrity
  failure deletes only the session's pending URI.
- The Manifest carries one or more ordered items; the single-item path is the N=1
  case of the same seam. Multi-item batch sequencing, the `ItemCommitted` record, and
  per-item Commit semantics are specified in ADR 0008.

## Consequences

- A complete file is never confused with sender EOF; the Android receiver owns the
  authoritative terminal result after Commit.
- Discovery, pairing UX, capability negotiation, background Receive Mode, batches,
  and resume can replace adapters or extend messages without replacing this seam.
- Debug certificate issuance, binding, expiry, rotation, and cleanup are explicit
  provisioning responsibilities. No private key or PKCS#12 file is packaged in an
  application build.
