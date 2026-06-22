import Foundation

public struct AuthenticatedPeerRoute: Hashable, Sendable {
    public let id: String
    public let endpointTicket: Data?

    public init(id: String, endpointTicket: Data? = nil) {
        precondition(!id.isEmpty)
        precondition(endpointTicket == nil || endpointTicket?.count == 32)
        self.id = id
        self.endpointTicket = endpointTicket
    }
}

public protocol AuthenticatedPeerRouteProviding: Sendable {
    func routes(peerID: Data, endpointHint: String?) async throws -> [AuthenticatedPeerRoute]
}

public protocol AuthenticatedPeerRouteAuthenticating: Sendable {
    func authenticate(
        peerID: Data,
        route: AuthenticatedPeerRoute
    ) async throws -> any OutboundTransferRecordChannel
}

public struct AuthenticatedPeerLocationPolicy: Equatable, Sendable {
    public let maximumRouteCount: Int
    public let stagger: Duration

    public init(
        maximumRouteCount: Int = 8,
        stagger: Duration = .milliseconds(250)
    ) {
        precondition(maximumRouteCount > 0)
        precondition(stagger >= .zero)
        self.maximumRouteCount = maximumRouteCount
        self.stagger = stagger
    }
}

public enum AuthenticatedPeerLocationError: Error, Equatable, Sendable {
    case unavailable
}

public actor AuthenticatedPeerLocationModule: OutboundAuthenticatedPeerLocating {
    private struct Winner: Sendable {
        let routeID: String
        let location: OutboundAuthenticatedPeerLocation
    }

    private let routes: any AuthenticatedPeerRouteProviding
    private let authenticator: any AuthenticatedPeerRouteAuthenticating
    private let policy: AuthenticatedPeerLocationPolicy
    private var lastSuccessfulRouteByPeer: [Data: String] = [:]

    public init(
        routes: any AuthenticatedPeerRouteProviding,
        authenticator: any AuthenticatedPeerRouteAuthenticating,
        policy: AuthenticatedPeerLocationPolicy = AuthenticatedPeerLocationPolicy()
    ) {
        self.routes = routes
        self.authenticator = authenticator
        self.policy = policy
    }

    public func locate(peerID: Data, endpointHint: String?) async throws -> OutboundAuthenticatedPeerLocation {
        guard peerID.count == 32 else { throw AuthenticatedPeerLocationError.unavailable }
        let candidates: [AuthenticatedPeerRoute]
        do {
            candidates = try await routes.routes(peerID: peerID, endpointHint: endpointHint)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw AuthenticatedPeerLocationError.unavailable
        }

        let ordered = orderedCandidates(candidates, peerID: peerID)
        guard !ordered.isEmpty else { throw AuthenticatedPeerLocationError.unavailable }
        let winner = try await race(Array(ordered.prefix(policy.maximumRouteCount)), peerID: peerID)
        lastSuccessfulRouteByPeer[peerID] = winner.routeID
        return winner.location
    }

    private func orderedCandidates(
        _ candidates: [AuthenticatedPeerRoute],
        peerID: Data
    ) -> [AuthenticatedPeerRoute] {
        var seen = Set<String>()
        var unique = candidates.filter { seen.insert($0.id).inserted }
        guard let preferred = lastSuccessfulRouteByPeer[peerID],
              let index = unique.firstIndex(where: { $0.id == preferred })
        else { return unique }
        unique.insert(unique.remove(at: index), at: 0)
        return unique
    }

    private func race(
        _ candidates: [AuthenticatedPeerRoute],
        peerID: Data
    ) async throws -> Winner {
        let authenticator = self.authenticator
        let stagger = policy.stagger
        return try await withThrowingTaskGroup(of: Winner.self) { group in
            for (index, route) in candidates.enumerated() {
                group.addTask {
                    if index > 0 { try await Task.sleep(for: stagger * index) }
                    try Task.checkCancellation()
                    let channel = try await authenticator.authenticate(peerID: peerID, route: route)
                    return Winner(
                        routeID: route.id,
                        location: OutboundAuthenticatedPeerLocation(
                            channel: channel,
                            endpointTicket: route.endpointTicket
                        )
                    )
                }
            }

            while !group.isEmpty {
                do {
                    if let winner = try await group.next() {
                        group.cancelAll()
                        return winner
                    }
                } catch is CancellationError where Task.isCancelled {
                    group.cancelAll()
                    throw CancellationError()
                } catch {
                    continue
                }
            }
            throw AuthenticatedPeerLocationError.unavailable
        }
    }
}
