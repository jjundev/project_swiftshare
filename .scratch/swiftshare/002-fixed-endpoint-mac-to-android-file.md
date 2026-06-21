---
id: SS-002
title: "Transfer one file from Mac to Android over a fixed endpoint"
status: ready-for-human
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [24, 25, 26, 27, 36, 37, 38, 39, 40, 46, 47, 49, 50, 64, 71]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

The architecture has no proven Transfer Session path demonstrating that authenticated streaming, approval, integrity, and Android Commit work together.

## What to build

Deliver the first complete Transfer Session: a Mac sender selects one file and a pre-provisioned development Paired Peer, connects to a fixed Android endpoint over TLS 1.3, shows the destination and payload summary, obtains receiver approval, streams bounded Chunks, verifies integrity, and commits the file through Android MediaStore. Both clients expose meaningful state and progress, and either participant can cancel without exposing a corrupt committed file.

This slice deliberately uses seeded development identities and a fixed reachable endpoint. Pairing and Discovery replace those temporary inputs in later slices without changing the Transfer Session contract.

## Scope

Includes one Mac-selected file sent to Android through a fixed endpoint; excludes Pairing, Discovery, batches, folders, clipboard Payloads, and durable resume.

## Acceptance criteria

- An offline LAN transfer completes from a Mac development build to an Android development build without a backend or internet access.
- Only the configured development Paired Peer can open the authenticated Transfer Session.
- The Mac shows destination, item count, total bytes, connecting, awaiting approval, transferring, verifying, and terminal states.
- Android shows the authenticated sender and payload summary and requires approval by default.
- Byte and item progress advance while file bytes are streamed in bounded memory.
- Each Chunk and the completed file are SHA-256 verified; integrity failure refuses Commit with an integrity error.
- Android keeps the destination pending until verification succeeds, then publishes it under Downloads/SwiftShare.
- Cancellation or failure leaves no item visible as a completed file.
- An existing destination is preserved and the incoming item receives a deterministic numeric suffix.
- Cross-language golden tests cover the record header, control messages, Manifest, progress, cancellation, and terminal result used by this path.

## Blocked by

- [SS-001](001-native-app-contract-foundation.md)
- [SS-015](015-macos-menu-bar-experience.md)

## Implementation decisions

- Use TCP with TLS 1.3 and explicitly bounded application records.
- Encode control records with Protobuf and carry file bytes in raw Chunk records; never embed an entire file in Protobuf.
- Start with a configurable 4 MiB Chunk target.
- Treat seeded identity and endpoint configuration as development-only adapters, not an alternate trust model.
- Represent destination publication as a storage-specific Commit rather than assuming atomic rename.

## Testing decisions

- Test the full Transfer Session with transport, approval, and destination fakes before running the same behavior on real devices.
- Cover fragmented and coalesced records, truncated headers, oversized lengths, duplicate and out-of-order Chunks, malformed control payloads, and corrupted bytes.
- Verify Android pending items cannot be observed as committed before final verification.
- Measure peak memory during a multi-gigabyte streaming test to catch whole-file buffering.

## Implementation evidence

The v1 wire contract, native codecs and role coordinators, sandboxed Mac sender,
Android TLS receiver and MediaStore Commit adapter, automated results, and remaining
real-device checks are recorded in
[`docs/acceptance/SS-002.md`](../../docs/acceptance/SS-002.md).

The implementation is ready for the hardware-dependent acceptance pass. A named
Android 14/API 34+ Device, its reachable LAN address, and an offline real-device run
are still required before this issue can be considered complete.
