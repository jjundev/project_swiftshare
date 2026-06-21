---
id: SS-011
title: "Resume interrupted transfers from durable Chunks"
status: ready-for-agent
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [41, 42, 43, 44, 45]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

Network loss or process recreation currently forces large transfers to restart even when verified durable Chunks could be reused safely.

## What to build

Persist sufficient authenticated Transfer Session state to resume an interrupted file transfer after network loss or process recreation. Reuse only Chunks known to be durable, and only when the same Paired Peer, Manifest, source identity, and staging destination can be re-established safely. Otherwise show why resume is unavailable and offer an explicit restart.

## Scope

Includes file-Chunk resume for durable sources and safe restart for non-durable sources; excludes arbitrary checkpointing of clipboard values or a general synchronization engine.

## Acceptance criteria

- After reconnecting, a durable source resumes from verified Chunks persisted as durable rather than restarting from byte zero.
- Resume state binds the Transfer Session, authenticated Peer identity, Manifest identity, source identity, completed Chunk map, and destination staging identity.
- Chunk completion is persisted only after its bytes are durable and its integrity check succeeds.
- The source is reopened and revalidated before resume; changed, missing, or unreadable sources cannot append to prior staging data.
- Android document-picker sources with persistable access can resume after process recreation.
- Temporary Sharesheet URIs clearly report when restart-safe resume is unavailable.
- Unsafe resume offers a clear restart action that discards or isolates obsolete staging state without exposing it as committed.
- Cancellation, unpair, identity change, expired permission, and final integrity failure reach distinct safe terminal states.

## Blocked by

- [SS-007](007-android-to-mac-file.md)
- [SS-008](008-native-send-entry-points-and-batches.md)

## Implementation decisions

- Treat durable Chunk acknowledgement as a storage guarantee, not merely receipt in memory.
- Authenticate every resumed connection and match it to the recorded Paired Peer.
- Separate resumable source capability from Payload type and native entry point.
- Do not require folder-tree resume beyond file Chunks unless it falls out safely from the same contract.

## Testing decisions

- Interrupt after every durable state transition, recreate each process, reconnect, and verify only durable Chunks are reused.
- Mutate source size, modification identity, and contents between attempts and verify resume refusal.
- Cover temporary Sharesheet grants, persistable document grants, bookmark loss, network changes, unpair, and identity reset.
- Corrupt persisted Chunk and staging metadata and verify safe restart or failure without Commit.
