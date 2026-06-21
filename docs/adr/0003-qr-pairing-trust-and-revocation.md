# ADR 0003: QR pairing, durable Peer trust, and revocation

- Status: Accepted
- Date: 2026-06-21

## Context

SS-002 authenticates manually entered development SPKI pins. SS-003 must replace
that adapter with one-time QR trust without mixing Pairing Session state into
Discovery or the closed Transfer v1 record registry.

## Decision

- Pairing uses a separate bounded Pairing v1 protocol over TLS 1.3. The Mac is the
  server; Android pins the exact leaf-certificate SHA-256 supplied by the QR.
- Each Device proves possession of the P-256 identity key over a transcript bound
  to the session, token hash, certificate fingerprint, nonces, identities, and
  display metadata. Both Devices independently derive and display a comparison
  code and require confirmation.
- The internal Peer identifier and subsequent mutual TLS pin are SHA-256 of the
  canonical SPKI. Friendly names never authorize a connection.
- Pairing tokens are 32 random bytes, admitted for 60 seconds, limited to three
  invalid attempts, claimed by one candidate, and invalidated on cancellation,
  timeout, failure exhaustion, or success.
- Paired Peer records are stored locally and are separate from endpoints. Android
  publishes an immutable synchronous trust snapshot for TLS and rechecks the
  serialized repository immediately after the handshake.
- Unpair durably revokes the local Peer before ending an active session. The sender
  uses `Cancel(peer_unpaired)` and the receiver returns the authoritative
  authenticated `TerminalResult(cancelled, authentication, peer_unpaired)`.
- Missing local identity material clears stale local Peer trust before a new
  identity is accepted.

## Consequences

- Captured QR codes cannot create another Peer after the first candidate claims or
  consumes the session.
- Certificate renewal with the same SPKI does not change Device identity; key loss
  does.
- Initial Mac development builds still use the existing Keychain provisioning
  command to create their non-exportable TLS identity. This ADR does not make the
  provisioning tool a second trust model.
