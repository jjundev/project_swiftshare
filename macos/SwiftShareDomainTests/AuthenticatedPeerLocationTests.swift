import Foundation
import Testing
import SwiftShareDomain

@Suite("Production Authenticated Peer Location")
struct AuthenticatedPeerLocationTests {
    @Test("The first authenticated route wins the bounded race")
    func firstAuthenticatedRouteWins() async throws {
        let routes = RouteProvider([
            AuthenticatedPeerRoute(id: "slow"),
            AuthenticatedPeerRoute(id: "fast", endpointTicket: Self.ticket),
        ])
        let authenticator = RouteAuthenticator([
            "slow": .successAfter(.milliseconds(100)),
            "fast": .successAfter(.zero),
        ])
        let module = AuthenticatedPeerLocationModule(
            routes: routes,
            authenticator: authenticator,
            policy: AuthenticatedPeerLocationPolicy(stagger: .milliseconds(10))
        )

        let location = try await module.locate(peerID: Self.peerID, endpointHint: nil)

        let channel = try #require(location.channel as? LocationChannel)
        #expect(channel.routeID == "fast")
        #expect(location.endpointTicket == Self.ticket)
    }

    @Test("The last successful route is attempted before the provider order")
    func lastSuccessFirst() async throws {
        let routes = RouteProvider([
            AuthenticatedPeerRoute(id: "first"),
            AuthenticatedPeerRoute(id: "last-success"),
        ])
        let authenticator = RouteAuthenticator([
            "first": .failure,
            "last-success": .successAfter(.zero),
        ])
        let module = AuthenticatedPeerLocationModule(
            routes: routes,
            authenticator: authenticator,
            policy: AuthenticatedPeerLocationPolicy(stagger: .milliseconds(20))
        )
        _ = try await module.locate(peerID: Self.peerID, endpointHint: nil)
        #expect(await authenticator.attemptedRoutes == ["first", "last-success"])

        await authenticator.set("first", to: .successAfter(.zero))
        await authenticator.resetAttempts()
        _ = try await module.locate(peerID: Self.peerID, endpointHint: nil)

        #expect(await authenticator.attemptedRoutes == ["last-success"])
    }

    @Test("Only the configured number of routes can authenticate")
    func boundedAttempts() async {
        let candidates = (0 ..< 12).map { AuthenticatedPeerRoute(id: "route-\($0)") }
        let authenticator = RouteAuthenticator(
            Dictionary(uniqueKeysWithValues: candidates.map { ($0.id, RouteBehavior.failure) })
        )
        let module = AuthenticatedPeerLocationModule(
            routes: RouteProvider(candidates),
            authenticator: authenticator,
            policy: AuthenticatedPeerLocationPolicy(maximumRouteCount: 8, stagger: .zero)
        )

        await #expect(throws: AuthenticatedPeerLocationError.unavailable) {
            try await module.locate(peerID: Self.peerID, endpointHint: nil)
        }
        #expect(await authenticator.attemptedRoutes.count == 8)
    }

    @Test("No candidate becomes a typed unavailable result")
    func noCandidates() async {
        let module = AuthenticatedPeerLocationModule(
            routes: RouteProvider([]),
            authenticator: RouteAuthenticator([:])
        )

        await #expect(throws: AuthenticatedPeerLocationError.unavailable) {
            try await module.locate(peerID: Self.peerID, endpointHint: nil)
        }
    }

    private static let peerID = Data(repeating: 0x44, count: 32)
    private static let ticket = Data(repeating: 0x55, count: 32)
}

private struct RouteProvider: AuthenticatedPeerRouteProviding {
    let values: [AuthenticatedPeerRoute]
    init(_ values: [AuthenticatedPeerRoute]) { self.values = values }
    func routes(peerID: Data, endpointHint: String?) async throws -> [AuthenticatedPeerRoute] { values }
}

private enum RouteBehavior: Sendable {
    case failure
    case successAfter(Duration)
}

private enum LocationTestError: Error {
    case authenticationFailed
}

private actor RouteAuthenticator: AuthenticatedPeerRouteAuthenticating {
    private var behavior: [String: RouteBehavior]
    private(set) var attemptedRoutes: [String] = []

    init(_ behavior: [String: RouteBehavior]) { self.behavior = behavior }

    func authenticate(
        peerID: Data,
        route: AuthenticatedPeerRoute
    ) async throws -> any OutboundTransferRecordChannel {
        attemptedRoutes.append(route.id)
        switch behavior[route.id] ?? .failure {
        case .failure:
            throw LocationTestError.authenticationFailed
        case .successAfter(let delay):
            if delay > .zero { try await Task.sleep(for: delay) }
            return LocationChannel(routeID: route.id)
        }
    }

    func set(_ routeID: String, to value: RouteBehavior) { behavior[routeID] = value }
    func resetAttempts() { attemptedRoutes = [] }
}

private actor LocationChannel: OutboundTransferRecordChannel {
    let routeID: String
    init(routeID: String) { self.routeID = routeID }

    func sendRecord(
        type: TransferRecordType,
        payload: Data,
        sessionID: UUID,
        protocolMajor: UInt16,
        protocolMinor: UInt16
    ) async throws {}

    func receiveRecord() async throws -> TransferWireRecord {
        throw LocationTestError.authenticationFailed
    }

    func close() async {}
}
