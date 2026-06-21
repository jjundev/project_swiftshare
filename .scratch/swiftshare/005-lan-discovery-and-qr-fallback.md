---
id: SS-005
title: "Discover Paired Peers on the LAN with QR endpoint fallback"
status: ready-for-human
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [10, 11, 12, 13, 14]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

Paired Devices still require a manually configured address and cannot explain common LAN reachability failures.

## What to build

Replace fixed endpoint configuration with LAN Mode Discovery. Devices advertise and browse a SwiftShare DNS-SD service using a session-rotating opaque identifier and protocol version. After connecting, the authenticated identity resolves the candidate to a Paired Peer. Provide a QR endpoint flow when multicast Discovery is unavailable and actionable diagnostics when the Devices lack IP reachability.

## Scope

Includes same-LAN mDNS/DNS-SD location and QR endpoint fallback; excludes Direct Mode and any cloud rendezvous service.

## Acceptance criteria

- A paired Mac and Android Device on a reachable LAN appear without entering an IP address.
- Advertisements contain a protocol version and rotating opaque identifier but no friendly Device name or trusted identity.
- Discovery candidates never authorize a Transfer Session before pinned authentication succeeds.
- Discovery identifiers rotate between application/advertising sessions without breaking Paired Peer resolution.
- A QR endpoint can establish the same authenticated Transfer Session when multicast is disabled.
- Client isolation, firewall, VPN, and unreachable-address failures produce distinct actionable errors; QR fallback does not claim to bypass them.
- IPv4, IPv6, dual-stack selection, interface-address changes, Wi-Fi loss, and reconnection are covered.
- The fixed-endpoint Mac-to-Android file scenario passes with both automatic Discovery and QR fallback.

## Blocked by

- [SS-003](003-qr-pairing-and-peer-management.md)
- [SS-004](004-session-scheduling-and-version-negotiation.md)

## Implementation decisions

- Advertise a SwiftShare TCP DNS-SD service over mDNS.
- Keep endpoint location, authenticated identity, and authorization as separate stages.
- Treat the QR fallback as endpoint and session-authentication material, not proof of network reachability.
- Prefer a reachable address using explicit dual-stack policy rather than assuming IPv4.

## Testing decisions

- Use discovery fakes for deterministic state-machine coverage and real devices for mDNS and interface behavior.
- Exercise multicast-disabled LANs, client isolation, VPN interference, address changes, sleep/wake, and Wi-Fi reconnect.
- Record discovery latency for later performance certification without making this slice responsible for final benchmark acceptance.

## Implementation evidence

Discovery/endpoint contracts, Android advertising and endpoint QR, Mac browsing
and QR scanning, authenticated route resolution, product UI cutover, automated
coverage, and remaining hardware checks are recorded in
[`docs/acceptance/SS-005.md`](../../docs/acceptance/SS-005.md).

The implementation is ready for the hardware-dependent acceptance pass. Real
IPv4/IPv6, multicast-disabled, client-isolation, firewall, VPN, interface-change,
sleep/wake, and Wi-Fi reconnect observations remain required.
