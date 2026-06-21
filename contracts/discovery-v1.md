# LAN Discovery and endpoint QR contract v1

SwiftShare advertises `_swiftshare._tcp` only while its mutually authenticated
TLS listener is ready. The DNS-SD instance name is `ss-` plus unpadded base64url
of a random 16-byte advertising identifier. TXT contains only `v=1` and `rid`
with that identifier. Neither field establishes identity or authorization.

## Authenticated resolution

After TLS 1.3 authenticates both endpoints against their current Paired Peer
directories, a discovery probe uses the magic `SSDR`, version bytes `1,0`, the
16-byte observed `rid`, and a 16-byte nonce. The reply adds one availability byte
and echoes the identifier and nonce. Probes are bounded separately from Transfer
workers and never acquire a Transfer Session scheduler lease.

## Endpoint QR

The URI is `swiftshare://connect/v1/` plus unpadded base64url of an envelope. Its
body is canonical bytes in this order: `SSEQ`, version 1.0, 32-byte Peer key ID,
network-order port, unsigned 64-bit Unix expiry seconds, 32-byte ticket, address
count, then family/length/address-byte entries. At most eight IPv4 or IPv6
addresses are allowed.

The envelope is `SSEC`, a two-byte body length, the exact body bytes, and a
64-byte IEEE P1363 P-256 signature over
`SHA-256("SwiftShare-Endpoint-QR-v1" || body)`. Decoders verify the stored Paired
Peer key and do not re-encode the body before verification.

The optional `SessionHello.endpoint_ticket` is validated only after mutual TLS
and before scheduler admission. It correlates the scanned route with one
authenticated attempt; it does not authorize an unpaired Peer, bypass version
negotiation, skip approval, or prove network reachability.

## Diagnostics

Diagnostics report observed evidence rather than claiming an unknowable network
cause. In particular, successful QR-authenticated unicast after automatic
Discovery failure is `automatic_discovery_unavailable`, not proof that an access
point specifically blocked multicast.
