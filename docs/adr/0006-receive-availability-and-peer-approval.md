# ADR 0006: Receive availability and receiver-owned Peer approval

- Status: Accepted
- Date: 2026-06-22

## Context

SS-005 leaves Android listener, advertisement, endpoint tickets, and approval UI
owned by `MainActivity`. That cannot support explicit background Receive Mode or
survive ordinary Activity recreation. The existing `TransferSessionRequest` also
contains a receiver policy even though the sender must never choose the receiver's
approval behavior.

## Decision

- Model receive availability, actual runtime availability, Peer approval policy,
  and active Transfer state independently.
- A process-scoped Android runtime owns one listener, advertiser, endpoint-ticket
  registry, and network epoch. Activity and foreground Service are lifecycle
  adapters, not competing owners.
- App-open availability starts with the visible Activity and stops, cancelling any
  active receive, when that Activity leaves the foreground. Explicit Receive Mode
  uses a `connectedDevice` foreground Service and a visible, non-dismissible
  notification with an immediate Stop action.
- Receive Mode is not enabled without visible notification permission. It is never
  launched from boot; a sticky Service may recreate an already-running mode after
  ordinary process reclamation.
- Notification Stop durably returns the policy to app-open-only before Service
  termination and suppresses app-open restart until the next foreground entry.
- Peer approval is receiver-owned, stored per Paired Peer, and defaults to manual.
  Peer documents use separate trust and policy revisions so changing auto-accept
  does not invalidate an authenticated trust token.
- A valid v1 Peer document migrates to v2 with manual approval. Corrupt, semantically
  invalid, or unknown documents fail closed and are not overwritten as an empty
  directory.
- Endpoint tickets are checked before the final trust validation immediately
  preceding scheduler admission. Policy is evaluated again after Manifest parsing.
- Unpair durably revokes trust and cancels only a session authenticated as that Peer.
- macOS launch-at-login uses `SMAppService.mainApp`; its system status is authoritative.
- The Mac sender gives LAN route resolution a bounded overall deadline and reports a
  typed unavailable result.

## Consequences

- Android background receive activity is visible and reversible; there is no silent
  always-on mode.
- Auto-accept cannot apply to an unauthenticated, unpaired, changed-key, or
  incompatible Peer.
- Activity recreation no longer creates a second receive stack.
- Stop and app-background transitions cancel the current Android receive rather than
  maintaining a second draining-Service lifecycle.
- SS-007 still owns the Mac destination and reverse payload/Commit implementation;
  SS-006 establishes its local approval and launch lifecycle seams.
