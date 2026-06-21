---
title: "PRD: SwiftShare Mac–Android local transfer"
status: ready-for-agent
type: prd
created: 2026-06-20
owner: project-owner
---

# PRD: SwiftShare Mac–Android local transfer

### Problem Statement

As the owner of both a MacBook and an Android device, the user lacks a dependable, private, Quick Share-like way to move files, folders, and clipboard content between those devices. Existing platform-native sharing experiences either target different platform combinations, require unsupported protocol interoperability, or introduce cloud storage and account dependencies for what should be a nearby local transfer.

The user needs a product that is fast enough to feel comparable to Quick Share, works in both directions, keeps data on the local connection, survives realistic interruptions, and remains understandable when platform background or network restrictions prevent a transfer. The initial product is for one person using their own paired devices, not for arbitrary nearby strangers.

### Solution

Build SwiftShare as independent native macOS and Android applications. The user pairs their devices once by scanning a QR code. After pairing, the devices discover each other on the same LAN, authenticate using pinned device identities, and transfer files, folder trees, or clipboard values over an encrypted local connection.

The macOS application is a SwiftUI menu-bar application with drag-and-drop and file selection. The Android application is a Jetpack Compose application that also appears in the system Sharesheet. Android receive availability is selectable in settings: receive only while the app is open, or remain in an explicit Receive Mode with a visible ongoing notification. Only Paired Peers are accepted; there is no Everyone mode in the initial product.

The first milestone delivers LAN Mode without a cloud service or account. A later milestone adds Direct Mode for use without a router, initially through an Android local-only hotspot and a guided, user-visible Mac connection flow. Cloud-backed identity and account features may be added later without being required by the local protocol.

### User Stories

1. As the owner of a Mac and Android device, I want a dedicated SwiftShare application on each device, so that I can transfer data without depending on unsupported Quick Share protocol compatibility.
2. As a new user, I want both applications to explain that they must be installed, so that I understand the product boundary before attempting a transfer.
3. As a new user, I want the Mac to display a pairing QR code, so that I can establish trust without typing a long secret.
4. As a new user, I want the Android app to scan the Mac's QR code, so that pairing is quick and resistant to nearby network impersonation.
5. As a security-conscious user, I want a pairing QR code to expire and work only once, so that a captured or reused code cannot silently pair another device.
6. As a user, I want each device to show the identity of the device being paired, so that I can stop if I scanned the wrong screen.
7. As a user, I want a clear pairing-success state on both devices, so that I know the relationship is ready for transfers.
8. As a user, I want to remove a Paired Peer, so that a lost, sold, reset, or reinstalled device can no longer transfer data.
9. As a user, I want reinstalled or identity-reset devices to require pairing again, so that stale trust does not survive key loss.
10. As a user on the same LAN, I want paired devices to appear automatically, so that I do not need to type an IP address for normal transfers.
11. As a privacy-conscious user, I want Discovery to avoid broadcasting my friendly device name, so that less personal information is exposed on the LAN.
12. As a privacy-conscious user, I want discovery identifiers to rotate between sessions, so that my device is harder to track over time.
13. As a user on a network that blocks multicast discovery, I want to connect a transfer session through a QR code, so that mDNS failure alone does not block me.
14. As a user on an isolated guest network, I want SwiftShare to explain that QR discovery cannot bypass client isolation, so that I receive an actionable error instead of waiting indefinitely.
15. As a Mac user, I want to drag a file onto the SwiftShare menu-bar window, so that sending requires minimal interaction.
16. As a Mac user, I want to select multiple files in one action, so that I can send a batch as one Transfer Session.
17. As a Mac user, I want to select a folder, so that its hierarchy and empty directories are preserved on Android.
18. As a Mac user, I want to send clipboard text, so that I can continue short text work on Android without creating a temporary file.
19. As an Android user, I want SwiftShare to appear in the system Sharesheet, so that I can send directly from Gallery, Files, and other applications.
20. As an Android user, I want to select multiple shared items, so that supported Sharesheet batches can transfer together.
21. As an Android user, I want to select files through SwiftShare's own picker, so that long transfers can retain durable access to their source.
22. As an Android user, I want to select and send a folder tree, so that directory-based work can move to the Mac.
23. As an Android user, I want to send clipboard text to the Mac, so that the clipboard feature works in both directions.
24. As a sender, I want to select the Paired Peer before sending, so that data never goes to an unintended device.
25. As a sender, I want to see the payload count, total size, and destination before confirming, so that I can catch mistakes.
26. As a receiver, I want incoming requests to show the sender and payload summary, so that I can accept or reject intentionally.
27. As a user, I want incoming transfers to require approval by default, so that pairing alone does not permit surprise writes.
28. As the owner of both paired devices, I want an optional auto-accept setting for a trusted peer, so that repetitive personal transfers can be frictionless.
29. As a user, I want auto-accept to remain disabled by default, so that enabling it is an explicit security choice.
30. As an Android user, I want to choose receive-only-while-open mode, so that SwiftShare consumes no persistent background resources when I do not need it.
31. As an Android user, I want to enable explicit Receive Mode, so that my Mac can initiate a transfer while the Android UI is not in front.
32. As an Android user, I want Receive Mode to show an ongoing notification, so that background discoverability is never hidden.
33. As an Android user, I want to stop Receive Mode from its notification, so that I can immediately become unavailable.
34. As a Mac user, I want SwiftShare to remain available from the menu bar while running, so that receiving does not require a full window.
35. As a Mac user, I want an optional launch-at-login setting, so that I can make the Mac available after signing in.
36. As a sender, I want to see connecting, awaiting approval, transferring, verifying, and completed states, so that progress is understandable.
37. As a sender, I want byte and item progress, so that I can estimate whether a large transfer is advancing.
38. As a receiver, I want to see progress and the current item, so that I can monitor storage and completion.
39. As either participant, I want to cancel an active Transfer Session, so that I remain in control of network and storage use.
40. As a user, I want cancellation to leave no committed corrupt file, so that failed work is not mistaken for complete work.
41. As a user, I want interrupted file transfers to resume from durable chunks where source access remains valid, so that temporary network loss does not restart large transfers.
42. As an Android Sharesheet user, I want SwiftShare to explain when restart-safe resume is unavailable for a temporary URI, so that the limitation is predictable.
43. As an Android user sending a very large file, I want an app-picker flow with durable document access, so that transfer can resume after process recreation.
44. As a user, I want the source to be revalidated before resume, so that SwiftShare never appends chunks from a changed file.
45. As a user, I want a clear restart option when resume is unsafe, so that I can recover without diagnosing protocol internals.
46. As a receiver, I want each chunk and completed file checked for integrity, so that corruption is detected before Commit.
47. As a receiver, I want verified Android files to become visible only after MediaStore publication, so that partial files do not appear complete.
48. As a receiver, I want verified Mac files placed into my chosen destination, so that SwiftShare respects sandbox and folder permissions.
49. As a user, I want existing files never overwritten silently, so that a transfer cannot destroy local data.
50. As a user, I want conflicting names to receive a predictable suffix, so that both versions remain available.
51. As a user moving a folder between case-sensitive and case-insensitive filesystems, I want conflicts detected before transfer, so that files are not merged accidentally.
52. As a user moving Unicode filenames, I want normalization collisions detected, so that visually equivalent paths do not overwrite each other.
53. As a security-conscious user, I want absolute paths, traversal components, unsafe links, and unsupported names rejected, so that a sender cannot escape the destination.
54. As a user, I want manifest size, path length, depth, and item-count limits, so that a malformed transfer cannot exhaust the receiver.
55. As a user, I want symbolic links excluded by default, so that folder transfer does not expose files outside the selected tree.
56. As a receiver with insufficient storage, I want SwiftShare to reject the request before bulk transfer, so that predictable failures happen early.
57. As a user, I want transfer errors to distinguish discovery, authentication, approval, permission, storage, integrity, and network failures, so that I know what to fix.
58. As a user, I want a Transfer History entry for completed, failed, rejected, and cancelled sessions, so that I can understand past activity.
59. As a user, I want Transfer History retained until I delete it, so that my personal record is persistent.
60. As a privacy-conscious user, I want history and logs to omit file and clipboard contents, so that diagnostics do not duplicate sensitive data.
61. As a user, I want diagnostic logs to be size-bounded and rotated, so that permanent diagnostics do not consume unlimited disk space.
62. As a user, I want to export diagnostic logs, so that I can investigate a problem during development.
63. As a user, I want to clear Transfer History and Diagnostic Logs separately, so that I control retained metadata.
64. As a user, I want LAN Mode to work without internet access, so that local transfer remains available offline.
65. As a user, I want SwiftShare to work without an account or backend, so that setup remains private and simple.
66. As a future user, I want local identity and transfer interfaces not to preclude an optional account layer, so that future cloud features do not require rewriting the transfer engine.
67. As a user, I want one active peer transfer at a time in the first release, so that progress and bandwidth behavior remain predictable.
68. As a user whose two devices initiate simultaneously, I want a deterministic busy or tie-break response, so that neither session hangs.
69. As a user, I want incompatible application versions to fail with a clear upgrade message, so that protocol mismatch is understandable.
70. As a user, I want compatible versions to negotiate optional capabilities, so that new features do not break basic transfer.
71. As a user, I want large transfers to use bounded memory, so that SwiftShare does not destabilize either device.
72. As a user, I want device discovery to complete within a median of three seconds on the reference network, so that nearby sharing feels immediate.
73. As a user, I want large-file throughput to reach at least 70% of the reference TCP baseline, so that protocol overhead remains reasonable.
74. As a user, I want comparable transfers to remain within 20% of the reference Quick Share result, so that SwiftShare feels competitively fast.
75. As a user, I want a 10 GB file to transfer and verify successfully, so that the product supports realistic large media and archives.
76. As a user, I want transfer memory usage to stay under the defined 150 MiB target, so that large files do not imply large memory use.
77. As a user without a shared router, I want a later Direct Mode, so that I can transfer while the devices are merely nearby.
78. As an Android user, I want SwiftShare to create a local-only hotspot for Direct Mode, so that the devices have a private local path.
79. As a Mac user, I want guided instructions to join the Direct Mode network, so that the flow remains possible without private macOS Wi-Fi APIs.
80. As a user, I want Direct Mode to reuse the same pairing, authentication, transfer, integrity, and history behavior as LAN Mode, so that transport choice does not change trust semantics.
81. As a user, I want SwiftShare to state when automatic Direct Mode connection is unavailable, so that public-platform limitations are transparent.
82. As a solo developer, I want the initial product to run from development builds without store packaging, so that implementation can focus on transfer correctness.
83. As a future maintainer, I want the product name treated as provisional until a public release, so that brand clearance can occur later.
84. As a future contributor, I want the project to remain private until the owner intentionally chooses a license, so that publication is not accidental.
85. As a Mac user, I want SwiftShare's primary controls in one compact menu-bar surface, so that sending, pairing, history, and settings stay close at hand.
86. As a sender, I want every quick entry point to preserve Peer and payload review before bytes are sent, so that convenience never bypasses intentional destination choice.
87. As a Mac user, I want the menu-bar surface to explain empty, offline, unavailable, busy, and failed states, so that the interface remains useful outside the happy path.
88. As a Mac user, I want a completed receive to offer a Finder reveal action, so that I can immediately locate the verified committed item.
89. As a Mac user, I want the compact interface to remain keyboard-, VoiceOver-, Dynamic Type-, and reduced-motion-friendly, so that menu-bar convenience does not reduce accessibility.

### Implementation Decisions

- SwiftShare is an independent protocol and product. Google Quick Share and Apple AirDrop interoperability are not implementation goals.
- The initial persona is one owner transferring among their own Paired Peers. Arbitrary nearby users, contacts, and Everyone visibility are excluded.
- The minimum platforms are macOS 14 and Android 14/API 34. Builds target the latest stable SDK available during implementation.
- The Mac client uses Swift and SwiftUI as a menu-bar application. The Android client uses Kotlin and Jetpack Compose.
- The exported Mac prototype at [`prototype/project/SwiftShare Mac.dc.html`](<../../prototype/project/SwiftShare Mac.dc.html>) is the visual and interaction reference for the macOS menu-bar experience. Its HTML, CSS, JavaScript, sample data, and timer-driven behavior are not production architecture.
- The Mac experience uses a compact home surface plus send review, active transfer, incoming review, Pairing Session, Transfer History, and settings views. Real application state drives those views through testable application-level models.
- Prototype-only controls such as simulated incoming transfers and simulated QR scans do not ship. Clipboard and drag/drop shortcuts still preserve explicit Peer and payload review before transfer.
- Existing product, security, privacy, lifecycle, and error requirements override omissions or demo shortcuts in the prototype. Production history remains aggregate and content-free, diagnostics can be cleared separately from history, and pairing includes remote identity confirmation.
- The repository is a monorepo. Runtime application code remains native and separate; the wire schema, state-machine specification, capability registry, and golden test vectors are shared contracts.
- The primary acceptance seam is the Transfer Session: given two Paired Peers, a source Payload set, receiver policy, and available transport, the system returns an authenticated terminal result and exposes only verified committed artifacts and the expected Transfer History.
- LAN Mode is implemented first. It requires only local IP connectivity and does not depend on a SwiftShare backend or internet connection.
- Discovery uses DNS-SD/mDNS with a SwiftShare TCP service. Advertisements expose a protocol version and session-rotating opaque identifier, not the friendly device identity.
- Trusted identity is learned only after the encrypted connection authenticates the Peer. Discovery identifiers are never treated as authorization.
- QR fallback supplies a session endpoint and authentication material when mDNS alone is unavailable. It does not claim to bypass AP client isolation, firewall rules, or missing IP reachability.
- The transport uses TCP with TLS 1.3. Application records include a bounded type, payload length, protocol version, Transfer Session identifier, and type-specific identifiers such as file and Chunk index.
- Control records are encoded with Protobuf. File bytes use explicitly bounded raw Chunk records rather than embedding entire files in Protobuf messages.
- The initial Chunk target is 4 MiB and is configurable through negotiated capabilities. Benchmarks may change the default without changing protocol semantics.
- Each Device owns a P-256 identity key protected by Keychain or Android Keystore.
- The first Pairing Session uses the Mac certificate fingerprint from the QR code to establish server-authenticated TLS. The one-time token is then validated and atomically consumed while the Android public identity and proof of possession are exchanged. Subsequent connections use mutually pinned authentication.
- Pairing tokens are random, short-lived, single-use, transcript-bound, and invalidated after success, timeout, cancellation, or a bounded number of failures.
- Only Paired Peers may request a Transfer Session. Incoming transfers require user approval by default. Auto-accept is an explicit per-peer setting.
- Android supports two user-selectable availability policies: visible only while the app is open, or explicit Receive Mode backed by a visible connected-device foreground service and notification controls.
- User-initiated Android sends use the platform's user-initiated data-transfer mechanism where appropriate. Receive Mode and active local-device communication use the lifecycle and foreground-service types required by the target OS.
- The Mac listens only while SwiftShare is running. Launch at login is optional and user-controlled.
- A Manifest represents files, folder entries, empty directories, clipboard Payloads, sizes, relative paths, metadata, and integrity information.
- Manifests and records have explicit maximum byte size, item count, folder depth, path length, and Payload size limits. Oversized input is rejected before allocation or disk writes.
- File-tree handling normalizes Unicode, detects case-fold and normalization collisions, rejects absolute or traversal paths, rejects unsafe platform names, and ensures every resolved destination remains within the selected root.
- Sender-side symlinks are excluded by default and selected source files are revalidated against the Manifest before and during transfer.
- File Chunks and completed files use SHA-256 integrity checks. Chunk completion state is persisted only after received bytes are durable.
- Resume state includes the Transfer Session, source identity, Manifest identity, completed Chunk map, destination staging identity, and authenticated Peer identity.
- Resume is allowed only when the source can be reopened and still matches its recorded identity. Otherwise the user receives a restart option.
- Android Sharesheet URI grants are treated as process-lifetime sources unless durable access is explicitly available. Restart-safe large transfers use SwiftShare's document picker with persistable access.
- Android receives files into the Downloads collection under a SwiftShare relative path. Items remain pending while written and verified, then become visible through MediaStore publication.
- The Mac writes to a user-selected destination retained through a security-scoped bookmark. Files are staged and committed only after verification.
- Commit behavior is storage-backend specific. The design does not assume general atomic rename support across Android providers.
- Existing destination items are never silently overwritten. Final names are reserved safely and receive deterministic numeric suffixes on conflict.
- The initial scheduler allows one active Peer Transfer Session. Simultaneous initiation produces a deterministic tie-break or authenticated busy response.
- Certificate rotation, key loss, app reinstall, unpair, and unpair-during-transfer are explicit state-machine events rather than incidental errors.
- Protocol compatibility uses a major/minor version plus capability negotiation. Unknown fields follow Protobuf compatibility rules; unknown required capabilities cause a clear rejection.
- Transfer History persists until explicit user deletion. It contains peer, direction, timestamps, status, aggregate sizes and counts, but no file contents or clipboard contents.
- Diagnostic Logs are local, exportable, redacted, size-bounded, and rotated. File and clipboard contents are never logged.
- Account and cloud functionality are not implemented initially, but identity, peer directory, and transfer policy are separated behind interfaces that can accept an optional future account layer.
- Direct Mode follows LAN Mode. Android creates a local-only hotspot; the Mac receives a guided public-API connection flow. Private macOS Wi-Fi APIs are prohibited.
- Direct Mode reuses the same Transfer Session, authentication, Payload, Commit, history, and test contracts as LAN Mode.
- Initial operation uses development builds only. Store submission, notarized distribution, automatic updates, public branding clearance, and repository publication are deferred.
- The working product name is SwiftShare. The repository remains private until the owner chooses an open-source license.

### Testing Decisions

- Good tests observe externally meaningful Transfer Session behavior: authentication result, acceptance policy, terminal status, committed artifacts, history, and user-visible error category. They do not assert private class layouts, callback ordering that is not contractual, or UI implementation details.
- The primary test seam is the complete Transfer Session contract. Supporting fakes may replace discovery, transport, clock, identity storage, and destination storage, but assertions remain at the Transfer Session boundary.
- Cross-language golden tests verify that Swift and Kotlin encode and decode the same Protobuf control messages, record headers, manifests, capability sets, and failure values.
- State-machine contract tests cover pairing, discovery resolution, approval, transfer, verification, Commit, cancel, rejection, timeout, busy, resume, unpair, and incompatible versions.
- Authentication tests cover wrong certificate fingerprints, expired or replayed pairing tokens, invalid proofs of possession, token race attempts, Peer key changes, and unpaired requests.
- Record parser tests cover fragmentation, coalescing, truncated headers, oversized lengths, unknown record types, duplicate Chunks, out-of-order Chunks, and malformed Protobuf payloads.
- File-tree tests cover traversal components, absolute paths, Unicode normalization collisions, case collisions, reserved names, symlinks, deep trees, too many entries, empty directories, and path-length limits.
- Storage acceptance tests verify that Android pending items do not appear committed before verification and that Mac staging items are not exposed as complete files.
- Conflict tests verify that existing destination items remain untouched and simultaneous final-name allocation cannot overwrite data.
- Integrity tests corrupt individual Chunks and final files and verify that Commit is refused with a clear integrity error.
- Resume tests interrupt after every durable transition, recreate the process, reconnect through authenticated transport, and verify that only durable Chunks are reused.
- Source-lifetime tests distinguish temporary Sharesheet URIs from persistable document selections and verify the documented restart behavior.
- Android lifecycle tests cover app-open mode, Receive Mode, notification stop, process recreation, foreground-service restrictions, screen lock, and network changes.
- macOS lifecycle tests cover menu-bar availability, launch-at-login choice, sandbox permissions, destination bookmark renewal, sleep/wake, and network changes.
- macOS interface tests cover the canonical home, send review, incoming review, pairing, transfer, history, and settings states plus empty, offline, busy, incompatible, expired, failed, and long-content variants. Snapshot checks protect visual hierarchy while accessibility tests protect keyboard navigation, labels, Dynamic Type, and reduced motion.
- Network tests cover IPv4, IPv6, dual-stack selection, changing interface addresses, multicast-disabled LANs, client isolation diagnostics, VPN interference, Wi-Fi loss, and reconnect.
- Clipboard tests cover empty values, Unicode, large bounded values, unsupported content types, rejection, and the guarantee that contents never enter logs.
- History and logging tests verify persistence, explicit deletion, redaction, bounded log rotation, and export behavior.
- Performance tests run on a named reference Mac, Android device, access point, distance, and congestion profile. They report median discovery time and transfer throughput percentiles rather than a single best run.
- LAN performance acceptance requires median discovery within three seconds, large-file application throughput of at least 70% of the same-path TCP baseline, no more than 20% regression from a comparable Quick Share reference where one exists, successful verified transfer of 10 GB, and peak transfer memory below 150 MiB per application.
- Direct Mode has the same behavioral suite as LAN Mode plus hotspot creation, guided join, teardown, return to prior network, and failure recovery.
- Real-device acceptance includes at least one supported Mac and one supported Android device. Emulator-only success is insufficient for discovery, lifecycle, storage, and throughput completion.
- There is no existing test prior art because the codebase begins empty. The Transfer Session acceptance seam, protocol golden vectors, and platform lifecycle suites become the canonical prior art for later features.

### Out of Scope

- Implementing or reverse-engineering Google Quick Share, Nearby Share, or Apple AirDrop protocols.
- Using the Pixel Quick Share-to-AirDrop path as SwiftShare's transport.
- Supporting unpaired nearby strangers, contact visibility, or an Everyone receive mode.
- Simultaneous transfer to multiple receiving devices in the initial release.
- Silent always-on Android receiving without a user-visible mode or notification.
- A SwiftShare cloud backend, mandatory account, contact directory, remote relay, or internet transfer in the initial release.
- Windows, iOS, iPadOS, ChromeOS, Linux, or web clients.
- Public release, Mac App Store or Play Store submission, notarized DMG distribution, automatic updates, telemetry service, or crash-reporting backend.
- Public repository publication or an open-source license until the owner makes a later decision.
- Private macOS Wi-Fi APIs or promises of an automatic routerless join when public APIs cannot provide one.
- General file synchronization, bidirectional folder mirroring, conflict merging, backup, version control, or remote device management.
- Executing, previewing, decompressing, or interpreting transferred files beyond the metadata required for safe storage.

### Further Notes

- Delivery order is: toolchain and repository foundation; Mac menu-bar experience shell; protocol and threat model; TLS-protected fixed-endpoint tracer bullet; Android and Mac storage Commit behavior; Pairing Session; mDNS Discovery; bidirectional transfer; platform integrations; multiple files, folders, and clipboard; durable resume and history; security and performance hardening; Direct Mode.
- Sandbox behavior, Android storage, and Android foreground lifecycle are tracer-bullet requirements rather than late hardening tasks because they shape the architecture.
- LAN Mode is the first usable product milestone. Direct Mode is required by the overall product direction but is not a blocker for validating the LAN milestone.
- The baseline Android toolchain is not installed in the current development environment. Xcode and Swift are available.
- The codebase contains no prior application implementation, protocol schema, test suite, or ADR. This PRD and the domain context establish the initial vocabulary and acceptance boundary.
