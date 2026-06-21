---
id: SS-008
title: "Send multi-item batches from native platform entry points"
status: ready-for-agent
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [15, 16, 19, 20, 25, 42]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

Single-file development flows do not integrate with the native Mac drag/drop or Android Sharesheet workflows users expect.

## What to build

Turn the proven single-file paths into the native sending experience. The Mac accepts drag-and-drop and multi-file selection from its menu-bar window. Android appears in the system Sharesheet for single and multiple supported items while retaining SwiftShare's own durable picker. All selected items form one bounded Manifest and one Transfer Session, with destination, count, and total size shown before confirmation.

## Scope

Includes native platform entry points and flat multi-file batches; excludes directory trees and restart-safe resume for temporary Sharesheet grants.

## Acceptance criteria

- Dropping one or more files on the Mac menu-bar window opens a confirmation for a selected Paired Peer.
- The Mac picker can select multiple files and send them as one Transfer Session.
- Android handles supported `ACTION_SEND` and `ACTION_SEND_MULTIPLE` Sharesheet inputs from representative Gallery and Files providers.
- SwiftShare's Android picker remains available for durable, restart-safe source selection.
- Every entry point shows destination, accepted payload count, total bytes, and any rejected unsupported item before confirmation.
- Batch progress exposes aggregate bytes, item progress, and current item on both Devices.
- Cancellation or failure preserves already-existing destination files and exposes no partially committed current item.
- Temporary Sharesheet URI grants are identified as process-lifetime sources and the UI explains their restart limitation before a long transfer starts.

## Blocked by

- [SS-005](005-lan-discovery-and-qr-fallback.md)
- [SS-007](007-android-to-mac-file.md)

## Implementation decisions

- Normalize all native entry points into one source-selection abstraction and the same Transfer Session contract.
- Treat a batch as one Manifest and Transfer Session, not a set of unrelated transfers.
- Do not claim durable resume for a URI unless persistable access has actually been obtained.
- Reject unsupported content types or unreadable sources before receiver approval where possible.

## Testing decisions

- Cover one and many items from drag-and-drop, Mac picker, Android Sharesheet, and Android document picker.
- Test mixed valid/invalid selections, revoked URI grants, empty batches, large batches, cancellation, and provider metadata anomalies.
- Assert the normalized Transfer Session result rather than platform callback ordering.
