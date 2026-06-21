# ADR 0004: Version-neutral session bootstrap and one-slot scheduler

- Status: Accepted
- Date: 2026-06-21

## Context

Transfer v1 previously rejected non-1.0 headers before it could return a useful
upgrade result, and the fixed Android listener could not accept a contender while
an active connection occupied its single executor. SS-004 requires authenticated
compatibility, capability negotiation, deterministic busy behavior, and a stable
simultaneous-initiation tie-break before approval or storage allocation.

## Decision

- Reuse the bounded 32-byte `SSHR` envelope as a version-neutral frame. Bootstrap
  types 240 and 241 use header protocol 0.0 and a separate Protobuf package.
- Authenticate TLS first. Exchange exactly one Hello and Decision before a
  Manifest. The accepted Decision carries the receiver offer and selected version,
  named capabilities, and Chunk size so the sender can recompute the choice.
- Use open canonical capability names. Unknown optional names are ignored and
  unknown required names reject before approval and storage.
- Put one Device-wide scheduler behind the Transfer Session boundary. Outbound
  attempts reserve before connect; inbound attempts acquire atomically before an
  accepted Decision.
- Resolve same-Peer pre-Decision initiation by authenticated SHA-256 SPKI key IDs.
  The smaller Device ID's outbound attempt wins. Other contention is ordinary
  busy. No queue or automatic retry is introduced.
- Keep scheduler leases in memory and release by ownership token on every terminal,
  timeout, or connection-close path.
- Split Android accepting from bounded connection workers. Only the admitted
  worker owns approval, active UI, cancellation, and destination state.
- Revalidate a generation-bound Peer trust token immediately before admission so
  unpair cannot admit a stale authenticated connection.
- Perform a coordinated development cutover: Manifest-first clients are rejected
  with `negotiation_required`; no legacy bridge is retained.
- SS-004 wires the existing Mac-outbound/Android-inbound tracer bullet. The reverse
  production adapter remains SS-007; dual schedulers and authenticated contract
  fakes certify simultaneous initiation in this slice.

## Consequences

- Major mismatch, capability mismatch, busy, and tie loss have authenticated,
  bounded bootstrap results even when no application major is selected.
- Every selected v1 record and Manifest/Chunk size is checked against the accepted
  negotiation.
- Existing development builds must be upgraded together.
- Additional transfer directions and transports reuse the same scheduler and
  bootstrap policy rather than inventing adapter-local contention rules.
