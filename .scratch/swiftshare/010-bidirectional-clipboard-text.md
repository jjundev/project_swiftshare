---
id: SS-010
title: "Transfer bounded clipboard text in both directions"
status: ready-for-agent
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [18, 23]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

Short text currently requires a temporary file even though clipboard values are a distinct product Payload.

## What to build

Add clipboard text as a first-class Payload that uses the same Peer selection, authentication, approval, progress, cancellation, and terminal-result behavior as files. Both clients can send and receive bounded Unicode text without creating a temporary visible file or recording its contents in retained metadata.

## Scope

Includes bounded plain-text clipboard transfer in both directions; excludes rich clipboard types and clipboard synchronization.

## Acceptance criteria

- Mac and Android can each send current plain-text clipboard content to a selected Paired Peer.
- The receiver sees the sender and a content-free summary before approval unless per-Peer auto-accept applies.
- Accepted text becomes available through the receiver's platform-appropriate clipboard interaction.
- Empty text, Unicode text, and text at the configured maximum behave consistently across both clients.
- Oversized values and unsupported clipboard types are rejected with a clear error before unbounded allocation.
- Clipboard contents never appear in protocol diagnostics, terminal-result descriptions, persisted metadata, or test failure output.
- Cancellation and rejection do not modify the receiving clipboard.

## Blocked by

- [SS-005](005-lan-discovery-and-qr-fallback.md)
- [SS-007](007-android-to-mac-file.md)

## Implementation decisions

- Represent clipboard text as a bounded Manifest Payload, not as a synthetic file.
- Share Transfer Session state and policy while keeping Commit storage-specific.
- Support plain text only in the initial product.

## Testing decisions

- Cover empty, Unicode, maximum-size, oversized, unsupported-type, rejected, cancelled, and successful values.
- Use sentinel secrets to assert contents never enter logs or history-facing result metadata.
- Run platform clipboard acceptance tests on supported real devices where emulator behavior differs.
