---
id: SS-003
title: "Pair devices with one-time QR trust"
status: ready-for-human
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [3, 4, 5, 6, 7, 8, 9]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

Development-seeded trust cannot securely establish or revoke the owner's real Device relationships.

## What to build

Replace seeded trust with a Pairing Session initiated by a Mac QR code and completed by Android scanning. The flow displays both Device identities, establishes pinned identities using proof of possession, reports success on both clients, and supports removing a Paired Peer. Key loss, reinstall, or identity reset must invalidate stale trust and require pairing again.

## Scope

Includes first pairing, persisted Paired Peer identity, unpair, key loss, and replay resistance; excludes LAN Discovery and account-backed identity.

## Acceptance criteria

- The Mac displays a QR code containing a server endpoint, certificate fingerprint, random one-time token, and expiry sufficient to begin the Pairing Session.
- Android scans the code, authenticates the Mac certificate fingerprint, and exchanges its public identity with proof of possession.
- Both clients display the remote Device identity before confirmation and show a clear paired-success state afterward.
- The token is short-lived, transcript-bound, single-use, and atomically consumed on success.
- Timeout, cancellation, a bounded number of failures, or successful use invalidates the token.
- Replaying a captured QR code or racing two clients cannot create a second Paired Peer.
- Removing a Paired Peer immediately prevents new Transfer Sessions; unpair during an active session reaches an explicit authenticated terminal state.
- A missing or changed identity key after reinstall/reset is treated as an unpaired Device.

## Blocked by

- [SS-002](002-fixed-endpoint-mac-to-android-file.md)

## Implementation decisions

- Store P-256 identity keys in Keychain and Android Keystore.
- Use the QR fingerprint for server-authenticated first contact, then require the token and proof-of-possession exchange.
- Use mutually pinned authentication for all connections after pairing.
- Keep Pairing Session state separate from Discovery metadata and Transfer Session authorization.

## Testing decisions

- Cover wrong fingerprints, expired and replayed tokens, invalid proofs, token races, Peer key changes, key loss, unpair, and unpair-during-transfer.
- Use an injected clock and identity store to make expiry and reinstall scenarios deterministic.
- Verify the existing fixed-endpoint file path succeeds using newly paired identities with seeded trust disabled.

## Implementation evidence

Pairing v1 contracts, native state machines, Mac QR/TLS server, Android scanner/TLS
client, persisted Peer directories, dynamic Android receive authorization, unpair
terminal behavior, automated results, and remaining real-device checks are recorded
in [`docs/acceptance/SS-003.md`](../../docs/acceptance/SS-003.md).

The implementation is ready for the hardware-dependent acceptance pass. SS-002's
offline real-device baseline and the paired-identity E2E observations remain
required before this issue can be considered complete.
