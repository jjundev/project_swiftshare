# Shared contracts

This directory is the only shared source surface between the native SwiftShare
applications. Runtime application implementations remain native and separate.

`fixtures/foundation-v1.json` is a semantic bootstrap fixture. It verifies that
Swift and Kotlin agree on the protocol version, a non-empty capability set, and
a terminal result representation. It is not a wire record.

SS-002 introduces the canonical Protobuf schema, bounded framing specification,
and golden byte vectors under `proto/`, `transfer-v1.md`, and `fixtures/`. The
foundation JSON remains a semantic bootstrap fixture, not a wire record.

SS-003 adds the independent `pairing-v1.md` protocol, its Protobuf schema, and a
shared semantic fixture. Pairing records never enter the closed Transfer v1 type
registry.

SS-004 adds `session-bootstrap.md`, a version-neutral negotiation schema, and a
shared outcome fixture. Bootstrap records authenticate, negotiate, and acquire the
one-Device session lease before the selected Transfer registry accepts a Manifest.

SS-005 adds `discovery-v1.md` and `fixtures/discovery-v1.json` for the private
DNS-SD advertisement, authenticated resolution probe, canonical endpoint QR body,
and cross-language signing digest. Discovery material never authorizes a Peer.

Fixture compatibility rules:

1. Existing fields and enum spellings are stable within a fixture version.
2. A breaking semantic change creates a new fixture version.
3. Both native test suites must consume the same file from this directory.
4. Generated or copied fixture sources are prohibited.
