# Transfer Session bootstrap contract

Every Paired-Peer Transfer connection completes mutual TLS before this protocol
is read. One TLS connection carries one Transfer Session attempt.

`SessionHello` may contain a 32-byte endpoint ticket produced by the signed LAN
endpoint QR. Receivers validate it after mutual TLS and before scheduler admission.
Its absence is normal for automatic DNS-SD Discovery, and its presence never
replaces Paired Peer trust or receiver approval.

The existing 32-byte `SSHR` envelope is version-neutral. Record types `240`
(`SessionHello`) and `241` (`SessionDecision`) are bootstrap records and use
protocol version `0.0` in the envelope. Their Protobuf payloads come from
`proto/swiftshare/transfer/bootstrap/bootstrap.proto` and are limited to 64 KiB.
All other record types are interpreted only after a successful decision and must
carry the selected application protocol version.

The sender sends exactly one Hello as its first application record. The receiver
answers with exactly one Decision carrying the same Transfer Session identifier.
The sender may not send a Manifest before an accepted Decision. A syntactically
valid non-Hello first record receives `PROTOCOL/negotiation_required`; malformed
or unbounded envelopes are closed without a response.

Versions are unsigned 16-bit `major`, `minimum_minor`, and `maximum_minor`
values. Major zero is reserved for the bootstrap envelope. The receiver selects
the highest common major and then the highest common minor. It does not silently
fall back to another version after capability or Chunk negotiation fails.

Capability names match `[a-z][a-z0-9_.-]{0,63}`. `file_transfer` is valid and
required for protocol 1.0. Unknown optional names are ignored. An unknown required
name rejects the attempt with `CAPABILITY/required_capability_unsupported` before
approval or destination allocation.

Both offers advertise inclusive Chunk-size ranges. The receiver selects the
largest value in their intersection. The initial range is 64 KiB through 4 MiB.
The Manifest `chunk_size` must equal the selected value; every non-final Chunk has
that exact data length and only the final Chunk may be shorter.

Before sending an accepted Decision, the receiver atomically revalidates trust and
acquires the Device-wide Transfer Session lease. Rejections use the bootstrap
Decision because an application major has not yet been selected. Initial stable
codes are `no_common_major`, `no_common_minor`, `no_common_chunk_size`,
`required_capability_unsupported`, `device_busy`,
`simultaneous_initiation_lost`, `negotiation_required`, `malformed_hello`,
`trust_revoked`, and `identity_collision`.

The scheduler has one Device-wide slot. A simultaneous incoming Hello from the
same Peer may arbitrate only against a pre-Decision outbound lease. The outbound
request from the lexicographically smaller authenticated Device key ID wins. All
other contention returns `BUSY/device_busy`. Busy attempts are not queued or
retried automatically; a retry uses a new session ID and TLS connection.
