---
id: SS-015
title: "Recreate the Mac menu-bar experience from the prototype"
status: ready-for-agent
type: issue
parent: .scratch/swiftshare/prd.md
created: 2026-06-21
user_stories: [24, 25, 34, 35, 36, 37, 38, 39, 57, 58, 62, 63, 85, 86, 87, 88, 89]
design_reference: prototype/project/SwiftShare Mac.dc.html
---

## Parent

[SwiftShare PRD](prd.md)

## Design reference

[Exported Mac prototype](<../../prototype/project/SwiftShare Mac.dc.html>)

The prototype defines the intended visual hierarchy and interaction flow. Recreate it in native SwiftUI; do not embed, ship, or structurally copy the exported HTML/CSS/JavaScript runtime.

## Problem

The current Mac development build contains only the product-boundary placeholder. Feature issues describe individual transfer behaviors, but there is no shared application shell or state model that turns them into the compact menu-bar experience established by the prototype. Implementing each feature directly into the placeholder would duplicate navigation and produce inconsistent loading, error, and terminal states.

## What to build

Build the native macOS menu-bar experience shell shown by the prototype. It includes home, send review, active transfer, incoming review, Pairing Session, Transfer History, and settings views, all driven by one testable application state model. Use injected fakes and preview fixtures for behavior not yet backed by production transport, identity, storage, discovery, history, or settings services.

This slice establishes the presentation contracts and integration seams that later feature issues fill. It must keep demo behavior visibly isolated from production builds and must not weaken the PRD's Peer selection, approval, privacy, Pairing Session, or error requirements.

## Scope

Includes the native SwiftUI menu-bar shell, navigation, canonical view states, responsive sizing, interaction contracts, fake-backed previews, visual regression coverage, and accessibility. Excludes production Pairing, Discovery, transfer transport, destination Commit, resume, persistent history, and diagnostic export implementations.

## Grilled decisions

### Confident

| # | Question | Decision | Why |
|---:|---|---|---|
| 1 | What authority does the prototype have? | Treat it as the Mac visual and interaction reference, not as production architecture or a new protocol specification. | The bundle explicitly asks for a native recreation and the repository already owns the domain and Transfer Session contracts. |
| 2 | What is the primary Mac surface? | Keep `MenuBarExtra` with window-style presentation, a roughly 392-point content width, a system-constrained maximum height, and an internally scrolling body. | This matches the prototype while respecting menu-bar placement and smaller displays. |
| 3 | Which top-level views form the shell? | Home, send review, active transfer, incoming review, pairing, history, and settings, with back navigation returning to home. | These are the seven explicit prototype views and align with existing issues. |
| 4 | What owns UI state? | A single application-level state model populated through injected service protocols; SwiftUI views remain projections of that state. | Timer-driven sample state cannot represent process recreation, failures, or real Transfer Session events reliably. |
| 5 | How does Peer selection work for quick sends? | Require an intended Paired Peer before transfer; one reachable Peer may be preselected, but the review screen always displays it before confirmation. | PRD stories 24–25 require intentional destination choice and review. |
| 6 | Does “Send clipboard” transmit immediately? | No. It creates a bounded clipboard draft and follows the same Peer/payload review contract as files. | The prototype shortcut otherwise bypasses the product's confirmation invariant. |
| 7 | Does the Mac expose a manual “Receive” or “simulate scan” action? | No production action. Incoming review is event-driven while the app runs, and Pairing Session progress comes from the real pairing service; simulation lives only in previews/tests. | Both controls are prototype harnesses, not user capabilities. |
| 8 | Is the prototype pairing happy path sufficient? | No. Add waiting, expiry, cancellation, remote identity confirmation, success, and actionable failure/retry states. | The existing Pairing Session requirements are security-critical and more complete than the mock. |
| 9 | How are transfer states represented? | Map connecting, awaiting approval, transferring, verifying, completed, cancelled, rejected, failed, busy, incompatible, and resumable interruption from domain/application events. | The UI must cover every stable terminal and recovery category, not only the animated happy path. |
| 10 | What may Transfer History display? | Peer, direction, timestamps, status, aggregate item count, and bytes; use generic payload labels rather than file names or clipboard contents. | The prototype's sample file names conflict with the PRD's aggregate, content-free history contract. |
| 11 | Which settings must appear? | Destination, per-Peer auto-accept, launch at login, Paired Peer management, diagnostic export, clear history, and clear diagnostics as independent actions. | The prototype omits separate log deletion even though story 63 requires it. |
| 12 | Which missing states are in scope? | No peers, multiple peers, Peer offline, network unavailable, expired pairing, denied permission, busy/incompatible Peer, failed transfer, empty history, long labels, and bounded large lists. | A compact shell that only renders one Pixel and happy paths is not an implementable product contract. |
| 13 | What does the home footer mean? | Show connection mode and an honest local reachability summary, with fallbacks when the network name is unavailable; never treat it as authenticated identity. | Network labels are presentation metadata and Discovery remains non-authoritative. |
| 14 | How strictly should native behavior match the mock? | Preserve hierarchy, spacing, emphasis, color intent, and transitions while allowing native focus, popover placement, text expansion, and accessibility behavior to win. | System behavior is part of a credible macOS menu-bar experience. |
| 15 | How do later feature issues integrate? | They replace fakes behind the shell's service and state-event seams instead of creating new parallel screens. | This keeps the UI coherent without coupling it to unfinished infrastructure. |

### Assumptions / needs you

| # | Question | Assumption used in this issue | Why confirmation may matter |
|---:|---|---|---|
| 16 | Is the exported look the release-level visual direction? | Yes; use it as the primary Mac design baseline, subject to native and accessibility corrections. | A later brand or visual redesign would change snapshot baselines and styling work. |
| 17 | Should the UI shell land before the fixed-endpoint tracer bullet? | Yes; establish the presentation seam immediately after SS-001, then wire real behavior incrementally. | Reversing the order favors protocol learning sooner but risks rebuilding the placeholder UI in several slices. |
| 18 | Does this prototype define Android UI? | No; it constrains only macOS, while Android keeps the behavior requirements in the existing issues. | Cross-platform visual parity would require a separate Android design direction. |

## Acceptance criteria

- The existing macOS 14 menu-bar-only application opens a native window-style surface matching the prototype's compact hierarchy without any runtime dependency on `prototype/`.
- Home renders useful zero-, one-, and many-Peer states, file drop/picker entry, clipboard entry, Pairing Session entry, history/settings navigation, and an honest LAN availability footer.
- A file, folder, or clipboard entry produces a send draft; no transfer begins until the intended Peer, accepted item count, total bytes, rejected items, and source-lifetime warning are shown and confirmed.
- Incoming review shows the authenticated Paired Peer, content-free payload summary, destination, and accept/decline actions; no production-only simulation control is visible.
- Pairing renders waiting, countdown/expiry, remote identity confirmation, success, cancellation, and failure/retry states without a production “simulate scan” control.
- Active transfer renders direction, Peer, phase, aggregate bytes, percentage, item progress/current item when available, cancellation, verification, and all required terminal/recovery states.
- A completed receive offers Reveal in Finder only for a verified committed destination URL.
- Transfer History uses content-free aggregate labels and renders completed, failed, rejected, and cancelled entries plus an empty state.
- Settings expose destination repair/change, per-Peer auto-accept, launch at login, unpair, redacted diagnostic export, clear history, and clear diagnostics as independent actions.
- Preview/test fixtures exercise every canonical and missing state without sleeping on timers or requiring a live Peer.
- Keyboard traversal, default/cancel actions, VoiceOver labels, text expansion, contrast, reduced motion, and long localized content remain usable within the menu-bar surface.
- SwiftUI snapshot or image-diff tests protect the canonical visual states, while behavior tests assert emitted user intents and application state transitions rather than private view structure.

## Blocked by

- [SS-001](001-native-app-contract-foundation.md)

## Implementation decisions

- Introduce presentation-specific value types for Peer availability, send drafts, incoming requests, transfer progress, history rows, pairing state, and settings state; do not expose transport or storage implementation objects directly to SwiftUI.
- Separate navigation state from Transfer Session state so closing/reopening the menu-bar surface does not cancel work or erase progress.
- Keep preview fixtures and fake event sources in development/test support. Production composition must fail clearly if a required service is unavailable rather than silently using sample data.
- Use platform APIs for drag/drop, file importing, accessibility, launch at login, and Finder reveal. Treat the HTML's exact DOM structure, generated QR art, fake menu bar, and wallpaper as non-product scaffolding.
- Centralize reusable visual tokens inside the Mac client so all seven views share spacing, typography, status colors, and motion behavior.

## Testing decisions

- Test the application state reducer/model independently from SwiftUI using injected clocks and service event streams.
- Snapshot canonical light/dark appearances at the reference width and at constrained height, then exercise text expansion and long localized labels separately.
- Assert drop, picker, Peer selection, confirmation, approval, decline, cancel, retry, unpair, clear, export, and reveal intents at public presentation seams.
- Verify preview and test-only simulation controls/sample values cannot enter a production build.
- Add accessibility audits for labels, focus order, keyboard actions, contrast, and reduced-motion alternatives.

## Plan

1. Extract reusable colors, typography, spacing, status treatments, container sizing, and accessibility motion rules from the prototype into native Mac presentation tokens.
2. Define the application state, navigation, user intents, service events, and fake fixtures for every decision-table state.
3. Build the shared menu-bar container, header/back behavior, scrolling body, and LAN status footer.
4. Implement home and send-review flows, including zero/many-Peer behavior, drop/picker/clipboard drafts, source warnings, and explicit confirmation.
5. Implement incoming review, pairing, and active/terminal transfer views with complete failure and recovery variants.
6. Implement aggregate Transfer History and settings, filling the prototype's missing empty state and independent diagnostic deletion.
7. Add native accessibility, localization stress cases, keyboard behavior, snapshot coverage, and presentation-model tests.
8. Replace fakes incrementally as SS-002, SS-003, SS-005, SS-006, SS-008, SS-010, and SS-012 deliver their production services.

To flip a decision, re-invoke with `#<n>=<value>`.
