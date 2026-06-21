---
id: SS-012
title: "Persist Transfer History and redacted diagnostics"
status: ready-for-agent
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [57, 58, 59, 60, 61, 62, 63]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

Terminal results disappear after a session and low-level failures are neither actionable to the owner nor safely exportable for diagnosis.

## What to build

Make terminal Transfer Session outcomes understandable and inspectable. Persist user-visible Transfer History for completed, failed, rejected, and cancelled sessions, categorize actionable errors, and maintain local size-bounded rotating Diagnostic Logs that can be exported without file or clipboard contents. Let the owner clear history and diagnostics independently.

## Scope

Includes local history, error categorization, bounded redacted logs, export, and independent deletion; excludes telemetry and remote crash reporting.

## Acceptance criteria

- Every completed, failed, rejected, and cancelled Transfer Session creates one durable history entry.
- Entries retain Peer, direction, timestamps, status, aggregate item count, and byte count until the user deletes them.
- History omits file contents, clipboard contents, secrets, tokens, raw keys, and sensitive path details.
- User-visible failures distinguish Discovery, authentication, approval, permission, storage, integrity, network, busy, and incompatible-version categories with an actionable next step.
- Diagnostic Logs are local, redacted, size-bounded, rotated, and exportable through each platform's user-visible flow.
- Clearing Transfer History does not clear Diagnostic Logs, and clearing logs does not clear history.
- Interrupted/recreated processes produce a single coherent terminal history outcome rather than duplicate success/failure entries.

## Blocked by

- [SS-006](006-receive-mode-and-peer-policy.md)
- [SS-007](007-android-to-mac-file.md)
- [SS-008](008-native-send-entry-points-and-batches.md)
- [SS-009](009-safe-folder-tree-transfer.md)
- [SS-010](010-bidirectional-clipboard-text.md)
- [SS-011](011-durable-transfer-resume.md)

## Implementation decisions

- Persist aggregate Transfer History separately from size-bounded technical Diagnostic Logs.
- Use stable error categories at the Transfer Session boundary and translate platform-specific causes into them.
- Redact at event construction time rather than relying only on export-time filtering.
- Keep history until explicit deletion; do not add telemetry or a remote logging backend.

## Testing decisions

- Exercise every terminal status and error category through the complete Transfer Session seam.
- Seed file names, clipboard values, tokens, keys, and paths with sentinel secrets and assert they do not appear in history, logs, or exports.
- Verify persistence, independent deletion, rotation bounds, export, crash/recreation behavior, and duplicate suppression.
