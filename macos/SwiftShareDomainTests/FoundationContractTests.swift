import Foundation
import Testing
import SwiftShareDomain

@Suite("Shared foundation contract")
struct FoundationContractTests {
    @Test("Swift decodes the canonical foundation fixture")
    func decodesCanonicalFixture() throws {
        let data = try Data(contentsOf: Self.fixtureURL)
        let contract = try JSONDecoder().decode(FoundationContract.self, from: data)

        #expect(contract.fixtureVersion == 1)
        #expect(contract.protocolVersion == ProtocolVersion(major: 1, minor: 0))
        #expect(contract.capabilities == [.fileTransfer])
        #expect(contract.terminalResult.status == .completed)
        #expect(Capability(rawValue: "future_optional")?.rawValue == "future_optional")
    }

    @Test("Product boundary copy states the independent local product boundary")
    func productBoundaryCopy() {
        #expect(ProductBoundary.message.contains("Mac"))
        #expect(ProductBoundary.message.contains("Android"))
        #expect(ProductBoundary.detail.contains("local network"))
        #expect(ProductBoundary.detail.contains("Quick Share"))
        #expect(ProductBoundary.detail.contains("AirDrop"))
    }

    private static var fixtureURL: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("contracts/fixtures/foundation-v1.json")
    }
}
