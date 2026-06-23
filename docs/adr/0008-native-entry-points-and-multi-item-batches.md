# ADR 0008: Native entry points and multi-item batches

- Status: Accepted
- Date: 2026-06-23

## Context

SS-008 turns the proven single-file paths into the native sending experience: Mac
drag-and-drop and multi-file selection, and the Android Sharesheet
(`ACTION_SEND`/`ACTION_SEND_MULTIPLE`) plus SwiftShare's own durable document picker.
All selected items must form one bounded Manifest and one Transfer Session, with
destination, count, and total size shown before confirmation.

The transfer v1 wire (ADR 0002) keyed every Chunk, Progress, and SenderFinished by
`item_id` and modelled the Manifest as a repeated `FileEntry`, but both sessions
enforced exactly one entry and looped over a single payload. Directory trees and
restart-safe resume for temporary Sharesheet grants are out of scope (SS-009, SS-011).

## Decision

- **In-place v1, no new capability.** There is no installed base, so the batch is an
  in-place extension of transfer v1 rather than a negotiated `batch_transfer`
  capability. Golden vectors are regenerated; `SessionNegotiator` is unchanged.
- **One Manifest, one Approval, sequential items, one TerminalResult.** The Manifest
  carries 1..512 ordered items with distinct ids and a bounded aggregate byte count.
  The receiver issues one batch-level Approval. The sender streams items in Manifest
  order; each item runs the existing per-Chunk Progress loop inside an outer per-item
  loop on both roles.
- **`ItemCommitted` (record type 8).** After committing a non-final item the receiver
  sends `ItemCommitted(item_id)`; the sender advances. The final item is acknowledged
  by the single session TerminalResult. Reusing Progress was rejected: both sender
  loops discard `Progress.phase`, so the signal would be ambiguous.
- **Incremental commit; partial batches are retained.** Each item commits as it
  verifies. On failure or cancellation already-committed items stay committed, only the
  in-flight Staging Reservation is aborted, and both roles report the artifacts/items
  committed so far. The receiver accumulates `CommittedArtifact`s and returns them on
  success, failure, and cancellation.
- **Artifact paths stay private and consistent across platforms.** `committed_artifact_id`
  is empty on the wire in both `ItemCommitted` and TerminalResult on macOS and Android;
  senders never depend on receiver-side paths (reconciles a prior Swift/Kotlin
  asymmetry). Receivers surface their own committed paths locally.
- **One source-selection abstraction per platform.** Each native entry point normalizes
  into an ordered payload list plus a per-item source lifetime and a rejected-item list.
  `OutboundTransferIntent` carries `payloads` (the single-payload initializer is the
  N=1 convenience).
- **Source lifetime is explicit.** Document-picker URIs take a persistable grant
  (durable); Sharesheet `EXTRA_STREAM` URIs are process-lifetime and never have
  `takePersistableUriPermission` called on them. The UI states the restart limitation
  before a process-lifetime batch starts. No byte-copy is made to fake durability.
- **Reject before the receiver.** Unsupported, unreadable, directory, or unsafe-named
  sources are excluded at selection and surfaced in review; an empty/all-rejected batch
  blocks send. No unsupported item reaches receiver approval.

## Consequences

- The single-item transfer is exactly the N=1 batch; the Transfer Session acceptance
  seam is unchanged, so existing contract tests keep their meaning.
- Cross-language golden vectors gain a multi-entry Manifest and an `ItemCommitted`
  vector; Swift and Kotlin must agree on both.
- Restart-safe resume for temporary grants remains unavailable by design and is called
  out to the user; durable resume is deferred to SS-011, directory trees to SS-009.
