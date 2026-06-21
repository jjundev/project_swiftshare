---
id: SS-014
title: "Connect through Android hotspot Direct Mode"
status: ready-for-agent
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [77, 78, 79, 80, 81]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

SwiftShare cannot transfer when the owner's Devices are nearby but share no reachable router-based LAN.

## What to build

Add routerless Direct Mode as a new connection path without creating a new trust or transfer model. Android creates a local-only hotspot and displays the information needed for the Mac to join. The Mac guides the owner through the public-API, user-visible connection flow and clearly states when automatic connection is unavailable. Once reachable, both Devices reuse Pairing, authentication, Transfer Session, Payload, Commit, resume, history, diagnostics, and error behavior from LAN Mode.

## Scope

Includes Android local-only hotspot, guided Mac join, teardown, and LAN-contract parity; excludes private Wi-Fi APIs and guaranteed automatic joining.

## Acceptance criteria

- Android can start a local-only hotspot through supported public APIs and displays an explicit Direct Mode state.
- The Mac presents accurate guided join instructions and required network information without private Wi-Fi APIs.
- The UI clearly states when the platform requires manual joining and never promises unsupported automatic connection.
- After joining, paired Devices authenticate and complete file, batch, folder, clipboard, cancellation, and resumable transfer scenarios through the existing contracts.
- Direct Mode preserves approval, per-Peer auto-accept, integrity, safe Commit, conflict, history, and diagnostic behavior from LAN Mode.
- Stopping Direct Mode tears down the hotspot, reports failures clearly, and guides recovery/return to the prior network where public APIs permit.
- Hotspot creation failure, join failure, lost connection, timeout, cancellation, and process recreation have actionable terminal behavior.
- The LAN Mode behavioral suite passes unchanged against the Direct Mode transport, supplemented by hotspot and join acceptance tests.

## Blocked by

- [SS-013](013-lan-mode-real-device-certification.md)

## Implementation decisions

- Model Direct Mode as endpoint establishment beneath the existing authenticated connection and Transfer Session layers.
- Use Android local-only hotspot and public macOS APIs only.
- Keep the connection flow explicit and user-visible; do not use private macOS Wi-Fi APIs.
- Preserve the same Paired Peer identities across LAN and Direct modes.

## Testing decisions

- Re-run LAN behavioral acceptance through Direct Mode on supported real devices.
- Add hotspot creation, guided join, teardown, return-to-prior-network, process recreation, and failure-recovery cases.
- Verify no transport-specific path bypasses authentication, approval, integrity, Commit, history, or redaction invariants.
