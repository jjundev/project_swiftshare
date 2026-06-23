# ADR 0007: Durable Android source and sandboxed Mac destination Commit

- Status: Accepted
- Date: 2026-06-22

## Context

The reverse LAN path must survive ordinary Android Activity or process recreation,
must work in the Mac App Sandbox, and must never publish a partial or overwritten
file. DNS-SD remains only a route candidate, and the existing device-wide session
admission rule must apply equally in both directions.

## Decision

- Android selects exactly one document with `ACTION_OPEN_DOCUMENT`, takes a
  persistable read grant, and atomically persists URI, display metadata, byte count,
  item identifier, and reviewed SHA-256. Replacing the draft releases the old grant.
- Preparation and transfer hash with bounded buffers. Before transfer, Android
  rechecks readability and metadata; the streamed byte count and digest must still
  match the reviewed draft or the sender cancels with `source_changed`.
- Android outbound work is foreground/process-bound. Leaving the visible app
  cancels an active outbound session; it does not introduce a second background
  service policy.
- The Mac advertises reverse availability only while it has at least one Paired Peer
  and a valid, writable security-scoped destination bookmark.
- Destination scope is held by the staging reservation through Commit or abort.
  Hidden staging files are created in the destination directory, synchronized, and
  promoted with exclusive atomic rename.
- Existing names are never overwritten. Commit tries the original basename, then a
  deterministic `name (n).ext` sequence, respecting the destination name limit.
- A durable staging journal records planned, staged, and committing phases. On the
  next destination preflight, abandoned staging entries are removed and synchronized.
- Reverse DNS-SD candidates become selectable only after mutually pinned TLS and a
  matching nonce/rotating-ID probe. The actual Transfer Session opens a new pinned
  TLS connection and authenticates the same Peer again.
- Both directions share one `TransferSessionScheduler` per Device. Reverse transfer
  reuses v1 Hello/Decision, Manifest, approval, Chunk, progress, sender-finished,
  cancellation, and terminal-result records.
- The Mac returns no local filesystem path in the wire terminal artifact field.

## Consequences

- Ordinary Android recreation does not silently change or lose the reviewed source;
  revoked or changed sources fail closed.
- A stale or revoked Mac bookmark makes the Device unavailable until the user repairs
  the destination.
- Crash, cancellation, integrity, storage, and network failures leave no visible
  partial final file. Recovery may delete an abandoned hidden stage on next preflight.
- Only one direction can occupy device transfer capacity at a time.
- Sharesheet entry, batches, folders, resume, and background Android outbound work
  remain outside SS-007.
