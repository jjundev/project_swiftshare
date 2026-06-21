---
id: SS-013
title: "Certify LAN Mode security and performance on real devices"
status: ready-for-agent
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [69, 70, 71, 72, 73, 74, 75, 76]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

Feature-complete LAN Mode is not releasable until its security, resilience, throughput, large-file, and memory claims pass on real reference devices.

## What to build

Harden and certify the complete LAN Mode product on a named reference Mac, Android Device, and access point. Close security and resilience gaps exposed by adversarial protocol, authentication, storage, lifecycle, and network testing, then meet the defined discovery, throughput, large-file, and memory acceptance envelope without weakening Transfer Session correctness.

## Scope

Includes adversarial hardening and documented real-device LAN benchmarks; excludes store-release packaging and Direct Mode.

## Acceptance criteria

- Authentication rejects wrong fingerprints, replayed/expired tokens, invalid proofs, unpaired requests, changed Peer keys, and token races without exposing application data.
- Record parsing safely rejects fragmentation edge cases, truncation, oversized lengths, unknown required records, malformed Protobuf, invalid identifiers, and duplicate/out-of-order Chunks.
- Network and lifecycle acceptance passes across IPv4, IPv6, dual stack, multicast-disabled LAN, client isolation diagnostics, VPN interference, Wi-Fi loss, reconnect, sleep/wake, screen lock, process recreation, and interface changes.
- Median Discovery completes within three seconds on the documented reference network.
- Large-file application throughput reaches at least 70% of same-path TCP baseline and is no more than 20% behind a comparable Quick Share reference where one exists.
- A 10 GB file transfers, verifies, and commits successfully in each supported direction.
- Peak transfer memory remains below 150 MiB per application.
- Results record hardware, OS versions, access point, distance, congestion, baseline method, medians, and relevant percentiles rather than a single best run.
- All earlier Transfer Session, storage, folder-safety, resume, history, and redaction suites remain green.

## Blocked by

- [SS-004](004-session-scheduling-and-version-negotiation.md)
- [SS-005](005-lan-discovery-and-qr-fallback.md)
- [SS-006](006-receive-mode-and-peer-policy.md)
- [SS-007](007-android-to-mac-file.md)
- [SS-008](008-native-send-entry-points-and-batches.md)
- [SS-009](009-safe-folder-tree-transfer.md)
- [SS-010](010-bidirectional-clipboard-text.md)
- [SS-011](011-durable-transfer-resume.md)
- [SS-012](012-history-errors-and-diagnostics.md)

## Implementation decisions

- Optimize only after profiling the named reference path and preserve bounded records, integrity, staging, and Commit semantics.
- Allow negotiated Chunk-size tuning without changing protocol meaning.
- Treat emulator and loopback results as development feedback, not final acceptance.
- Keep all benchmarks local and backend-independent.

## Testing decisions

- Automate repeatable benchmark runs and capture medians and throughput/memory percentiles.
- Run adversarial tests at the authenticated Transfer Session boundary and storage acceptance tests on real devices.
- Compare against the same-path raw TCP baseline and document Quick Share methodology where a comparable result exists.
- Retain only redacted diagnostic artifacts suitable for local export.
