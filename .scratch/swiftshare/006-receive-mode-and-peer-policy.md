---
id: SS-006
title: "Control receive availability and trusted-Peer approval policy"
status: ready-for-human
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [28, 29, 30, 31, 32, 33, 34, 35]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

The owner lacks explicit, platform-correct control over background availability and whether a trusted Peer may bypass manual approval.

## What to build

Give the owner explicit control over when each Device is available and how a Paired Peer is approved. Android supports receive-only-while-open and explicit Receive Mode with a visible ongoing notification and stop action. The Mac remains available through its menu-bar application while running and optionally launches at login. Incoming approval remains the default, with opt-in auto-accept stored per Paired Peer.

## Scope

Includes Android availability modes, Mac runtime availability, launch at login, and per-Peer approval policy; excludes silent always-on receiving.

## Acceptance criteria

- Android offers app-open-only and Receive Mode policies and defaults to the less persistent option.
- Receive Mode uses the Android 14 lifecycle and foreground-service type required for connected-device communication.
- An ongoing notification visibly identifies Receive Mode and stops availability immediately from its action.
- Process recreation, screen lock, foreground-service restrictions, network changes, and notification stop lead to understandable availability states.
- Auto-accept is disabled by default and can be enabled or revoked independently for each Paired Peer.
- Auto-accept never applies to an unauthenticated, unpaired, changed-key, or incompatible Peer.
- The Mac accepts requests while its menu-bar application is running and exposes an optional launch-at-login choice.
- The Mac-to-Android file path succeeds in both approval and auto-accept modes and fails promptly when Android is unavailable.

## Blocked by

- [SS-003](003-qr-pairing-and-peer-management.md)
- [SS-005](005-lan-discovery-and-qr-fallback.md)

## Implementation decisions

- Model availability separately from pairing and per-Peer approval policy.
- Require a user-visible foreground service for background Android receiving; do not implement silent always-on receiving.
- Store launch-at-login and Receive Mode choices locally and make them reversible.

## Testing decisions

- Add Android lifecycle tests for app-open mode, Receive Mode, process recreation, notification stop, screen lock, network changes, and OS restrictions.
- Add macOS lifecycle tests for menu-bar availability, launch-at-login, sleep/wake, and network changes.
- Verify approval-policy behavior at the Transfer Session boundary using authenticated and unauthenticated Peer cases.

## Implementation evidence

Receive availability, connected-device foreground Service, notification Stop,
per-Peer receiver-owned approval, launch-at-login, automated checks, and remaining
hardware observations are recorded in
[`docs/acceptance/SS-006.md`](../../docs/acceptance/SS-006.md).

The implementation is ready for the hardware-dependent acceptance pass. Android
process/lock/foreground-service behavior, real network changes, notification Stop,
Mac launch-at-login, and manual/auto-accept LAN transfers still require real-device
observation.
