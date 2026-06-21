---
id: SS-009
title: "Transfer safe folder trees in both directions"
status: ready-for-agent
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [17, 22, 51, 52, 53, 54, 55, 56]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

Flat file batches cannot preserve folder structure and are unsafe when paths collide or resolve differently across platform filesystems.

## What to build

Extend a Transfer Session to carry a bounded folder-tree Manifest in either direction while preserving hierarchy and empty directories. Validate the entire tree and reserve safe destinations before bulk transfer so platform case rules, Unicode normalization, unsafe paths, links, unsupported names, excessive resource use, or insufficient storage cannot merge or escape the selected destination.

## Scope

Includes bidirectional folder selection, bounded Manifest preflight, safe path handling, and empty directories; excludes synchronization, merging, and link transfer.

## Acceptance criteria

- Mac and Android can each select and send a folder tree to the other Device.
- Nested hierarchy and empty directories are preserved after verified Commit.
- Absolute paths, traversal components, destination escapes, unsafe links, and unsupported platform names are rejected before bulk bytes are accepted.
- Symbolic links are excluded by default and cannot expose files outside the selected tree.
- Case-folding and Unicode-normalization collisions are detected for the receiver's filesystem semantics before transfer.
- Manifest byte size, item count, depth, path length, individual Payload size, and aggregate size limits are enforced before unbounded allocation or disk writes.
- Available storage is checked before approval/transfer and an insufficient-storage result is actionable.
- Existing destination items are not overwritten; predictable suffixing preserves both trees without unsafe merging.

## Blocked by

- [SS-008](008-native-send-entry-points-and-batches.md)

## Implementation decisions

- Define files, directories, and empty directories explicitly in the Manifest.
- Normalize and validate relative paths before resolving them beneath the selected destination root.
- Apply receiver-platform case and normalization semantics during preflight.
- Revalidate sender source identity and type before and during streaming.
- Keep safety limits negotiated but always bounded by a receiver-owned maximum.

## Testing decisions

- Cover traversal, absolute paths, Unicode normalization collisions, case collisions, reserved names, symlinks, deep trees, excessive entries, empty directories, path-length limits, and destination escapes.
- Test the same golden Manifests in Swift and Kotlin.
- Verify storage preflight and Commit behavior on real Android storage and a sandboxed Mac destination.
