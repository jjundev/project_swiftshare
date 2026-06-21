---
id: SS-001
title: "Bootstrap native clients and the shared contract harness"
status: ready-for-agent
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-20
user_stories: [1, 2, 65, 66, 82, 83, 84]
---

## Parent

[SwiftShare PRD](prd.md)

## Problem

No runnable clients, shared contracts, or canonical tests exist, so no end-to-end product slice can be built or verified consistently across Swift and Kotlin.

## What to build

Create the development-build foundation for the native macOS and Android SwiftShare clients and a shared contract harness. Both applications must launch into a minimal product-boundary experience that explains that SwiftShare must be installed on both devices. Establish shared protocol vocabulary and golden fixtures that can be consumed independently from Swift and Kotlin without coupling the native runtime implementations.

The foundation must preserve clean seams for identity, Peer directory, transport, Transfer Session, and destination storage so a future optional account layer does not become a dependency of LAN Mode.

## Scope

Includes development-build application shells and cross-language contract fixtures; excludes production transfer behavior, distribution, accounts, and public release work.

## Acceptance criteria

- A macOS 14 SwiftUI menu-bar development build launches and shows the SwiftShare product boundary.
- An Android 14/API 34 Jetpack Compose development build launches and shows the same product boundary.
- The repository has repeatable build and test entry points for both native clients, with a clear diagnostic when the local Android SDK is unavailable.
- Swift and Kotlin tests independently read at least one shared golden fixture and agree on the protocol version, capability set, and terminal result representation.
- No account, backend, Quick Share/AirDrop interoperability, store packaging, public licensing, or publication dependency is introduced.

## Blocked by

None - can start immediately.

## Implementation decisions

- Keep macOS and Android runtime code native and separate; share specifications, schemas, capability definitions, and golden vectors only.
- Treat the Transfer Session as the primary acceptance seam and inject discovery, transport, identity storage, clock, and destination storage behind it.
- Use the domain language from `CONTEXT.md` in both clients and contracts.
- Keep the working name SwiftShare provisional and the repository private.

## Testing decisions

- Exercise public build/test commands from a clean checkout or equivalent clean workspace.
- Assert behavior at the application shell and shared-contract boundaries, not private class layouts.
- Verify golden fixtures in both languages whenever a suitable Android SDK is available; do not silently skip unavailable-toolchain coverage.

## Implementation evidence

Reproducible commands, observed results, and the remaining Android SDK-dependent
checks are recorded in [`docs/acceptance/SS-001.md`](../../docs/acceptance/SS-001.md).
