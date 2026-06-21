# ADR 0005: Private LAN Discovery and authenticated endpoint QR

- Status: Accepted
- Date: 2026-06-21

## Context

SS-005 replaces manually entered addresses without allowing DNS-SD metadata or a
scanned address to become identity or authorization. It must preserve multiple
routes, IPv4/IPv6 behavior, listener lifecycle, and the SS-003/SS-004 trust and
scheduling contracts.

## Decision

- Advertise `_swiftshare._tcp` only after the TLS listener reports its actual
  bound port. Each listener/network epoch gets a random opaque instance and `rid`.
- TXT exposes only discovery schema version and rotating identifier.
- Resolve an advertisement with a bounded `SSDR` probe after mutually pinned TLS.
  Probe handshakes and work have quotas separate from Transfer workers and never
  acquire a scheduler lease.
- Keep candidate routes in memory. Merge routes only after authentication maps
  them to the same Paired Peer; one route loss does not make the Peer offline.
- The Mac connection module owns Bonjour routes, QR routes, exact-pin TLS racing,
  ticket use, and typed connection failure. Callers select only a Peer and
  optionally provide a scanned QR.
- Endpoint QR bodies are canonical signed bytes. Their short-lived ticket is
  validated after TLS and before Transfer admission but never replaces Paired
  Peer authentication, negotiation, scheduling, or approval.
- Android retains an explicit “Available while open” action in SS-005. SS-006
  owns automatic foreground availability and background Receive Mode.
- Diagnostics use qualified evidence such as `automatic_discovery_unavailable`,
  `client_isolation_suspected`, and `vpn_interference_suspected`.

## Consequences

- Friendly names and stable identities are not multicast.
- Fixed host and pin fields leave product UI but remain injectable in tests.
- Android uses an ephemeral listening port and DNS-SD carries the bound port.
- QR cannot bypass firewall, client isolation, VPN routing, Wi-Fi loss, or an
  unreachable address.
