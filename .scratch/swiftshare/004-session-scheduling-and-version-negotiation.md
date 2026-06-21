---
id: SS-004
title: "Negotiate protocol capabilities and schedule one Transfer Session"
status: ready-for-human
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [67, 68, 69, 70]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

Without explicit compatibility and scheduling rules, mismatched or simultaneous clients can hang, corrupt protocol state, or behave unpredictably.

## What to build

Make every authenticated connection negotiate protocol major/minor versions and optional capabilities before application data. Enforce the initial one-active-Peer-Transfer-Session policy and return a deterministic authenticated result when both Devices initiate simultaneously or a Device is already busy.

## Scope

Includes version/capability negotiation and one-session arbitration; excludes multiple concurrent transfers and multi-device fan-out.

## Acceptance criteria

- Compatible clients negotiate a common minor version, Chunk size, and optional capability set before sending a Manifest.
- A major-version mismatch returns a clear user-visible upgrade error on both Devices.
- Unknown optional capabilities do not break the basic file Transfer Session.
- Unknown required capabilities reject the request before approval or disk allocation.
- A Device runs at most one active Peer Transfer Session.
- Simultaneous initiation resolves through a documented stable tie-break; the losing request receives an authenticated busy response and neither client hangs.
- Golden vectors define compatible, incompatible, unknown-optional, unknown-required, busy, and tie-break outcomes in Swift and Kotlin.

## Blocked by

- [SS-002](002-fixed-endpoint-mac-to-android-file.md)
- [SS-003](003-qr-pairing-and-peer-management.md)

## Implementation decisions

- Use major/minor protocol versions plus a registry of named capabilities.
- Follow Protobuf unknown-field compatibility rules while making required capabilities explicit.
- Put scheduling behind the Transfer Session boundary so later transports reuse the same policy.
- Base the tie-break on authenticated stable Device identities, never rotating Discovery identifiers.

## Testing decisions

- Run state-machine contract tests for compatible and incompatible versions, busy state, simultaneous initiation, cancellation, and subsequent retry.
- Cross-test the same capability fixtures in Swift and Kotlin.
- Assert terminal behavior rather than internal lock or callback ordering.

## Implementation evidence

The version-neutral bootstrap contract, cross-language negotiation codecs and
fixtures, Device-wide scheduler, Mac sender integration, concurrent Android
receiver admission, trust-generation revalidation, and automated results are
recorded in [`docs/acceptance/SS-004.md`](../../docs/acceptance/SS-004.md).

The implementation is ready for the hardware-dependent acceptance pass. A paired
real-device transfer, incompatible-build observation, and active-session busy
observation remain required. The actual Android-outbound/Mac-inbound adapter is
owned by SS-007; SS-004 certifies the shared simultaneous-initiation scheduler
contract until that path exists.
