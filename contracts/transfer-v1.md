# Transfer wire contract v1

SS-002 uses one TLS 1.3 byte stream in each direction. The TLS layer must
authenticate the configured development Paired Peer before an application record
is read. The version-neutral bootstrap in `session-bootstrap.md` negotiates and
admits the session before this v1 registry accepts a Manifest.

## Record header

Every application record begins with this 32-byte, network-byte-order header:

| Offset | Width | Field | v1 value |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `SSHR` (`53 53 48 52`) |
| 4 | 1 | header version | `1` |
| 5 | 1 | record type | registry below |
| 6 | 2 | flags | `0`; all bits reserved and rejected in v1 |
| 8 | 2 | protocol major | `1` |
| 10 | 2 | protocol minor | `0` |
| 12 | 4 | payload length | unsigned bytes following this header |
| 16 | 16 | Transfer Session identifier | UUID bytes in RFC 4122 order |

The v1 type registry is `1 Manifest`, `2 Approval`, `3 Progress`, `4
SenderFinished`, `5 Cancel`, `6 TerminalResult`, `7 Chunk`, and `8 ItemCommitted`.
Values `0` and `9...239` and `242...255` are reserved. Global envelope types
`240 SessionHello` and `241 SessionDecision` are defined by the bootstrap contract
rather than v1. Because v1 has no optional-record flag, an unknown type or
any non-zero flag is a protocol error.

Control payloads are Protobuf messages from
`proto/swiftshare/transfer/v1/transfer.proto` and may not exceed 256 KiB. No record
may exceed 16 MiB. A v1 Chunk record is further restricted to 4 MiB of file data
plus its 64-byte prelude.

## Raw Chunk payload

A Chunk payload contains a fixed prelude followed immediately by raw file bytes:

| Offset | Width | Field |
|---:|---:|---|
| 0 | 16 | item UUID, RFC 4122 byte order |
| 16 | 4 | zero-based Chunk index |
| 20 | 8 | absolute file byte offset |
| 28 | 4 | raw data length |
| 32 | 32 | SHA-256 of the raw data |
| 64 | data length | raw file bytes |

`payload_length` must equal `64 + data_length`. The receiver accepts only the
expected item, next index, and exact next offset. A duplicate, gap, overlap, hash
mismatch, or length mismatch fails the Transfer Session before Commit.

## Session path

1. After TLS authentication, the sender and receiver exchange bootstrap Hello and
   Decision, select protocol 1.0, capabilities, and Chunk size, and acquire the
   receiver session lease.
2. The sender emits one Manifest describing the whole batch: one or more ordered
   `FileEntry` items using the selected Chunk size. A Manifest carries at least one
   and at most 512 items with distinct item ids; the receiver additionally bounds the
   aggregate declared byte count. An empty, oversized, or duplicate-id Manifest is a
   protocol error.
3. The receiver answers with one Approval for the entire batch. Rejection is terminal
   and allocates no destination.
4. The sender streams the items in Manifest order. For each item it emits ordered
   Chunks; the receiver writes and hashes bounded slices and reports cumulative
   Progress after each verified Chunk of that item.
5. After an item's Chunks, the sender emits SenderFinished for that item. The receiver
   verifies the cumulative length and whole-file digest and commits the item. For every
   non-final item it answers with `ItemCommitted(item_id)`, signalling the sender to
   advance; for the final item it answers with one session TerminalResult. A committed
   item is durable even if a later item fails — the sender never receives receiver-side
   paths (`committed_artifact_id` stays empty on the wire on both platforms).
6. Either side may emit Cancel. Each item's publication is its own Commit linearization
   point: already-committed items remain committed, and only the in-flight item's pending
   destination is discarded. After the final-item TerminalResult `completed`, the batch
   is final.

For Peer revocation, a sender emits `Cancel` with reason `peer_unpaired`. The
receiver deletes any pending destination and returns
`TerminalResult(cancelled, authentication, peer_unpaired)`. A receiver that
initiates revocation sends that TerminalResult directly. Senders accept an early
TerminalResult while awaiting Approval or verified Progress. If Commit linearizes
first, completion remains final; if revocation linearizes first, cancellation wins.

Connect, approval, and idle timeouts are configurable engineering policy. Initial
development defaults are 15, 120, and 60 seconds respectively; only verified
progress resets the idle deadline.

## Golden vectors

Fixed-layout headers and Chunk preludes use exact-byte vectors. Protobuf fixtures
pair immutable reference bytes with semantic expectations. Swift and Kotlin must
decode the same bytes and each implementation's encoded output must decode to the
same semantics in the other implementation; Protobuf byte canonicality is not
assumed.
