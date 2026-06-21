# SwiftShare domain context

## Product boundary

SwiftShare is an independent local-transfer product for a single owner's macOS and Android devices. It benchmarks the experience of Quick Share, but it does not implement or claim compatibility with Google Quick Share or Apple AirDrop protocols.

The first delivery milestone transfers data over the same LAN. A later milestone adds a routerless direct mode using only public platform APIs and an explicit, user-visible connection flow.

## Ubiquitous language

- **Device**: A Mac or Android device running SwiftShare.
- **Peer**: A remote SwiftShare device visible to the local Device.
- **Paired Peer**: A Peer whose identity key has been verified and stored by the local Device.
- **Pairing Session**: The one-time QR-assisted exchange that establishes trust between two Devices.
- **Discovery**: Locating a SwiftShare Peer on the current network without establishing its trusted identity.
- **Discovery Candidate**: An unauthenticated DNS-SD or QR route that may lead to a Peer.
- **Advertising Epoch**: One continuous listener/network availability period with one rotating opaque identifier.
- **Authenticated Peer Location**: An in-memory set of routes mapped to a Paired Peer only after mutually pinned TLS.
- **Endpoint Ticket**: Short-lived QR correlation material validated after authentication but never used as Peer authorization.
- **Receive Mode**: The user-selected policy controlling when an Android Device advertises and accepts incoming requests.
- **Receive Availability Policy**: The local Device choice between Android availability only while the app is open and explicit background Receive Mode. It controls new admission, not Peer identity.
- **Peer Approval Policy**: A receiver-local, per-Paired-Peer choice between manual approval and auto-accept. It is never supplied or weakened by the sender.
- **Transfer Session**: One authenticated attempt to send one or more Payloads from a sender to a receiver.
- **Payload**: A file, folder tree, or clipboard value included in a Transfer Session.
- **Manifest**: The bounded description of all Payload entries, metadata, paths, sizes, and integrity information for a Transfer Session.
- **Chunk**: A bounded segment of a file Payload used for streaming, verification, and resume.
- **Commit**: Making a fully received and verified Payload visible in its final destination.
- **Transfer History**: User-visible metadata about completed, failed, rejected, or cancelled Transfer Sessions.
- **Diagnostic Log**: Size-bounded technical events used to diagnose SwiftShare without recording file or clipboard contents.
- **LAN Mode**: Discovery and transfer while both Devices can communicate on the same IP network.
- **Direct Mode**: A later routerless connection flow in which Android creates a local-only network and the Mac joins through a user-visible public-API flow.

## Invariants

- Only a Paired Peer can send or receive application data.
- Auto-accept applies only after current Paired Peer trust, compatibility, and Transfer admission have been revalidated by the receiver.
- Discovery metadata never establishes identity; identity is accepted only after cryptographic authentication.
- A received file is not committed until its integrity check succeeds.
- SwiftShare never silently overwrites an existing destination item.
- File and clipboard contents never appear in Transfer History or Diagnostic Logs.
- The LAN milestone does not require an internet connection or a SwiftShare backend.
