#if MAC_APP_ADAPTER_TESTS
import Foundation
import Testing

@Suite("Mac receive destination adapter")
struct MacReceiveDestinationAdapterTests {
    @Test("Reconciliation removes an abandoned hidden stage and is idempotent")
    func reconcileAbandonedStage() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("swiftshare-stage-test-\(UUID().uuidString)", isDirectory: true)
        let destination = root.appendingPathComponent("destination", isDirectory: true)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let stageName = ".swiftshare-stage-test"
        let stage = destination.appendingPathComponent(stageName)
        try Data("partial".utf8).write(to: stage)
        let journal = MacStagingJournal(url: root.appendingPathComponent("journal.json"))
        try journal.upsert(MacStageRecord(
            id: UUID(),
            destinationPath: destination.path,
            bookmark: Data(),
            stageName: stageName,
            finalName: nil,
            phase: .staged
        ))
        let resolved = MacDestinationBookmarkStore.Resolved(
            url: destination,
            displayName: "destination",
            bookmark: Data()
        )

        try journal.reconcile(resolved)
        try journal.reconcile(resolved)

        #expect(!FileManager.default.fileExists(atPath: stage.path))
    }
}
#endif
