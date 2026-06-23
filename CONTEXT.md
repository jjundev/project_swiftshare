# SwiftShare domain context

## Product boundary

SwiftShare is an independent local-transfer product for a single owner's macOS and Android devices. It benchmarks the experience of Quick Share, but it does not implement or claim compatibility with Google Quick Share or Apple AirDrop protocols.

The first delivery milestone transfers data over the same LAN. A later milestone adds a routerless direct mode using only public platform APIs and an explicit, user-visible connection flow.

## Ubiquitous language

- **Device**: A Mac or Android device running SwiftShare.
- **Peer**: A remote SwiftShare device visible to the local Device.
- **Paired Peer**: A Peer whose identity key has been verified and stored by the local Device.
- **Pairing Session**: The one-time QR-assisted exchange that establishes trust between two Devices. It begins when the QR is issued and ends only after both Devices durably record the Paired Peer and acknowledge that commit, or after a terminal failure.
- **Discovery**: Locating a SwiftShare Peer on the current network without establishing its trusted identity.
- **Discovery Candidate**: An unauthenticated DNS-SD or QR route that may lead to a Peer.
- **Advertising Epoch**: One continuous listener/network availability period with one rotating opaque identifier.
- **Authenticated Peer Location**: An in-memory set of routes mapped to a Paired Peer only after mutually pinned TLS.
- **Endpoint Ticket**: Short-lived QR correlation material validated after authentication but never used as Peer authorization.
- **Receive Mode**: The user-selected policy controlling when an Android Device advertises and accepts incoming requests.
- **Receive Availability Policy**: The local Device choice between Android availability only while the app is open and explicit background Receive Mode. It controls new admission, not Peer identity.
- **Peer Approval Policy**: A receiver-local, per-Paired-Peer choice between manual approval and auto-accept. It is never supplied or weakened by the sender.
- **Transfer Admission**: The receiver-local decision that an authenticated and compatible Transfer Session may occupy receive capacity. It is distinct from Peer Approval, which decides whether an admitted Manifest may proceed.
- **Transfer Session**: One user-confirmed attempt to send one or more Payloads to a Paired Peer. It begins when the sender confirms the Peer and Payload selection, includes locating and reauthenticating the Peer plus receiver admission and approval, and ends with one terminal outcome after Commit or failure.
- **Transfer Cancellation**: A request to end an active Transfer Session for a stated reason, such as user action, Peer revocation, or Receive Mode shutdown. The Transfer Session remains responsible for reaching its single terminal outcome.
- **Payload**: A file, folder tree, or clipboard value included in a Transfer Session.
- **Durable Source**: A sender-side Payload reference whose platform permission and reviewed identity survive ordinary UI or process recreation. Its bytes are still revalidated while streaming.
- **Receive Destination**: A receiver-owned directory or collection location whose current permission is checked before availability and again before reservation.
- **Staging Reservation**: A receiver-local, non-final item that owns destination access from creation through abort or Commit and is never exposed as a successful Payload.
- **Manifest**: The bounded description of all Payload entries, metadata, paths, sizes, and integrity information for a Transfer Session.
- **Chunk**: A bounded segment of a file Payload used for streaming, verification, and resume.
- **Commit**: Making a fully received and verified Payload visible in its final destination.
- **Transfer History**: User-visible metadata about completed, failed, rejected, or cancelled Transfer Sessions.
- **Diagnostic Log**: Size-bounded technical events used to diagnose SwiftShare without recording file or clipboard contents.
- **LAN Mode**: Discovery and transfer while both Devices can communicate on the same IP network.
- **Direct Mode**: A later routerless connection flow in which Android creates a local-only network and the Mac joins through a user-visible public-API flow.

## Invariants

- Every Transfer Session ends with exactly one terminal outcome, including attempts that cannot obtain an Authenticated Peer Location.
- A Receive Availability or Advertising Epoch change stops new Transfer Admission but does not by itself cancel an already active Transfer Session; that session continues until its connection fails or it is explicitly cancelled.
- Only a Paired Peer can send or receive application data.
- Auto-accept applies only after current Paired Peer trust, compatibility, and Transfer admission have been revalidated by the receiver.
- Discovery metadata never establishes identity; identity is accepted only after cryptographic authentication.
- A received file is not committed until its integrity check succeeds.
- Losing Durable Source or Receive Destination access fails the Transfer Session without exposing a partial final item.
- SwiftShare never silently overwrites an existing destination item.
- File and clipboard contents never appear in Transfer History or Diagnostic Logs.
- The LAN milestone does not require an internet connection or a SwiftShare backend.
