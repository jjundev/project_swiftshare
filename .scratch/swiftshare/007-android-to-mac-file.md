---
id: SS-007
title: "Transfer one durable Android file to a Mac destination"
status: ready-for-human
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [21, 24, 25, 26, 27, 36, 37, 38, 39, 40, 43, 46, 48, 49, 50]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

The proven transfer only runs Mac-to-Android and does not validate durable Android sources or sandbox-safe Mac destination Commit.

## What to build

Add the reverse single-file path. Android selects a file through SwiftShare's document picker with persistable source access, selects a discovered Paired Peer, and sends it through the existing Transfer Session. The Mac obtains and retains permission for a user-selected destination, stages bytes outside the visible final item, verifies integrity, and safely commits without overwriting an existing file.

## Scope

Includes one durable document-picker file sent Android-to-Mac; excludes Sharesheet entry, batches, folders, clipboard Payloads, and resume.

## Acceptance criteria

- Android can select a document with persistable access and show the Mac destination, item count, and total size before confirmation.
- Both clients expose sender/receiver progress, current item, approval, cancel, verification, and terminal states.
- Mac destination access survives ordinary relaunch through a valid security-scoped bookmark and asks the user to repair permission when needed.
- Received bytes remain staged until all Chunk and final-file integrity checks pass.
- Cancellation, permission loss, storage failure, network failure, and integrity failure expose no corrupt committed file.
- Existing files remain untouched and a deterministic numeric suffix is reserved safely under concurrent name allocation.
- A large selected document streams with bounded memory and without loading the complete file into either process.

## Blocked by

- [SS-002](002-fixed-endpoint-mac-to-android-file.md)
- [SS-003](003-qr-pairing-and-peer-management.md)
- [SS-005](005-lan-discovery-and-qr-fallback.md)

## Implementation decisions

- Use Android's document picker and persistable grants for the durable source path.
- Represent the Mac destination through a security-scoped bookmark and storage-specific staging/Commit adapter.
- Reuse the same Manifest, Chunk, authentication, approval, and terminal-result contracts as Mac-to-Android.

## Testing decisions

- Verify bookmark renewal, destination permission loss, sandbox behavior, name conflicts, cancellation, and integrity refusal.
- Recreate the Android process after selection and verify the persistable document remains readable.
- Run the reverse path on at least one supported Mac and Android Device; emulator-only storage acceptance is insufficient.

## Implementation evidence

- Added the production Kotlin outbound and Swift inbound Transfer Session roles with
  shared device-wide admission, protocol negotiation, approval, bounded Chunk
  streaming, progress validation, cancellation, integrity verification, and terminal
  outcomes.
- Added durable Android document drafts, reverse authenticated LAN discovery, fresh
  pinned TLS transfer connections, and sender UI.
- Added writable Mac destination bookmarks, preflight/repair gating, same-directory
  staging journal, exclusive atomic Commit with numeric suffixes, reverse listener,
  advertisement, and receiver UI.
- Recorded the architecture in ADR 0007 and automated/real-device evidence in
  `docs/acceptance/SS-007.md`.
- Automated domain tests and both application builds pass. Physical-device,
  sandbox-permission, fault-injection, collision-race, relaunch recovery, and large
  document observations remain required, so this issue is `ready-for-human`.
- Follow-up review fixes serialize Android outbound state/send admission, roll back
  failed URI grants, generation-gate Mac listener restarts, reject empty Chunks, and
  add automated regressions for these paths, deterministic destination suffixes, and
  idempotent abandoned-stage journal reconciliation in the Xcode test target.
