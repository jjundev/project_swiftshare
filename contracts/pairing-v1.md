# Pairing wire contract v1

Pairing is a separate TLS 1.3 application protocol from Transfer v1. The Mac is
the TLS server and Android is the client. The QR payload pins the exact SHA-256
digest of the Mac leaf certificate; hostname and public-PKI validation are not
authorization inputs for this first contact.

The QR string is `swiftshare://pair/v1/` followed by unpadded base64url of the
encoded `QrPayload`. Decoders reject payloads over 2 KiB, unknown major versions,
non-UUID session identifiers, non-P-256 certificates, tokens other than 32 bytes,
fingerprints other than 32 bytes, ports outside `1...65535`, and expired payloads.

## Framing

After TLS, every record is a one-byte type followed by a four-byte unsigned
network-order payload length and one Protobuf payload. Payloads are limited to
64 KiB. Types are `1 ClientStart`, `2 ServerChallenge`, `3 ClientProof`,
`4 ServerProof`, `5 ClientDecision`, `6 CommitReceipt`, `7 CommitAck`, and
`8 PairingError`. Unknown types fail closed.

## Transcript and proof

The transcript is SHA-256 over the ASCII domain `SwiftShare-Pairing-v1`, followed
by fixed-width session UUID, token SHA-256, QR certificate SHA-256, client nonce,
server nonce, and length-prefixed canonical SPKI/name/platform values in Mac then
Android order. Names are NFC-normalized UTF-8 with a 128-byte maximum. Control and
bidirectional override characters are rejected.

Each Device signs `SHA-256(role-domain || transcript)` with its non-exportable
P-256 identity key. Signatures use the 64-byte IEEE P1363 `r || s` form. The TLS
leaf key and the Mac descriptor SPKI must be equal. The Android certificate and
descriptor SPKI must likewise be equal.

The six-digit comparison code is independently derived after both proofs verify.
Success requires explicit acceptance on both Devices. The Mac durable commit of
the Peer plus consumed-session recovery record is followed by a signed receipt;
Android durably stores the Mac Peer and acknowledges the receipt before either UI
reports final success.

## Limits and lifetime

- QR admission: 60 seconds.
- Confirmation after a valid proof claims the session: 120 seconds.
- Invalid token, malformed identity, or invalid proof budget: 3.
- At most 4 pre-claim TLS connections and one claimed candidate.
- A cancellation, timeout, exhausted failure budget, or successful commit closes
  the QR to new candidates.
- A consumed recovery record is retained for 10 minutes and only the same
  candidate key with a fresh proof may retrieve its receipt.
