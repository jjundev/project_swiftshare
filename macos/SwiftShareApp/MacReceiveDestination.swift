import Darwin
import Foundation
import SwiftShareDomain

enum MacDestinationError: Error, LocalizedError {
    case permissionRequired
    case invalidName
    case storage(String)

    var errorDescription: String? {
        switch self {
        case .permissionRequired: "Choose or repair the Mac destination folder."
        case .invalidName: "The incoming file name is not safe on this Mac."
        case .storage(let code): "Destination storage failed: \(code)"
        }
    }
}

final class MacDestinationBookmarkStore: @unchecked Sendable {
    static let shared = MacDestinationBookmarkStore()

    private struct Document: Codable {
        let schema: Int
        let bookmark: Data
        let displayName: String
    }

    struct Resolved: Sendable {
        let url: URL
        let displayName: String
        let bookmark: Data
    }

    private let lock = NSLock()
    private let url: URL

    init(url: URL? = nil) {
        if let url { self.url = url }
        else {
            let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("SwiftShare", isDirectory: true)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            self.url = directory.appendingPathComponent("destination-v1.json")
        }
    }

    func save(_ folder: URL) throws {
        let accessed = folder.startAccessingSecurityScopedResource()
        defer { if accessed { folder.stopAccessingSecurityScopedResource() } }
        let values = try folder.resourceValues(forKeys: [.isDirectoryKey])
        guard values.isDirectory == true else { throw MacDestinationError.permissionRequired }
        let bookmark = try folder.bookmarkData(
            options: [.withSecurityScope],
            includingResourceValuesForKeys: [.isDirectoryKey],
            relativeTo: nil
        )
        try write(Document(schema: 1, bookmark: bookmark, displayName: folder.lastPathComponent))
    }

    func resolve() throws -> Resolved {
        let document = try read()
        var stale = false
        let folder: URL
        do {
            folder = try URL(
                resolvingBookmarkData: document.bookmark,
                options: [.withSecurityScope],
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
        } catch {
            throw MacDestinationError.permissionRequired
        }
        let access = folder.startAccessingSecurityScopedResource()
        guard access else { throw MacDestinationError.permissionRequired }
        defer { folder.stopAccessingSecurityScopedResource() }
        guard (try? folder.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else {
            throw MacDestinationError.permissionRequired
        }
        var bookmark = document.bookmark
        if stale {
            do {
                bookmark = try folder.bookmarkData(
                    options: [.withSecurityScope],
                    includingResourceValuesForKeys: [.isDirectoryKey],
                    relativeTo: nil
                )
                try write(Document(schema: 1, bookmark: bookmark, displayName: folder.lastPathComponent))
            } catch {
                throw MacDestinationError.permissionRequired
            }
        }
        return Resolved(url: folder, displayName: folder.lastPathComponent, bookmark: bookmark)
    }

    func displayName() -> String? { try? read().displayName }
    func clear() { lock.withLock { try? FileManager.default.removeItem(at: url) } }

    private func read() throws -> Document {
        try lock.withLock {
            guard FileManager.default.fileExists(atPath: url.path) else {
                throw MacDestinationError.permissionRequired
            }
            let value = try JSONDecoder().decode(Document.self, from: Data(contentsOf: url))
            guard value.schema == 1 else { throw MacDestinationError.permissionRequired }
            return value
        }
    }

    private func write(_ document: Document) throws {
        try lock.withLock {
            try JSONEncoder().encode(document).write(to: url, options: [.atomic])
            try Self.syncFile(url)
            try Self.syncDirectory(url.deletingLastPathComponent())
        }
    }

    static func syncFile(_ url: URL) throws {
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.synchronize()
    }

    static func syncDirectory(_ url: URL) throws {
        let descriptor = Darwin.open(url.path, O_RDONLY)
        guard descriptor >= 0 else { throw MacDestinationError.storage("directory_open_failed") }
        defer { Darwin.close(descriptor) }
        guard Darwin.fsync(descriptor) == 0 else { throw MacDestinationError.storage("directory_sync_failed") }
    }
}

struct MacStageRecord: Codable, Equatable {
    enum Phase: String, Codable { case planned, staged, committing }
    let id: UUID
    let destinationPath: String
    let bookmark: Data
    let stageName: String
    var finalName: String?
    var phase: Phase
}

final class MacStagingJournal: @unchecked Sendable {
    static let shared = MacStagingJournal()

    private struct Document: Codable { let schema: Int; var records: [MacStageRecord] }
    private let lock = NSLock()
    private let url: URL

    init(url: URL? = nil) {
        if let url { self.url = url }
        else {
            let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("SwiftShare", isDirectory: true)
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            self.url = directory.appendingPathComponent("staging-journal-v1.json")
        }
    }

    func upsert(_ record: MacStageRecord) throws {
        try lock.withLock {
            var document = try readUnlocked()
            document.records.removeAll { $0.id == record.id }
            document.records.append(record)
            try writeUnlocked(document)
        }
    }

    func remove(_ id: UUID) throws {
        try lock.withLock {
            var document = try readUnlocked()
            document.records.removeAll { $0.id == id }
            try writeUnlocked(document)
        }
    }

    func reconcile(_ resolved: MacDestinationBookmarkStore.Resolved) throws {
        try lock.withLock {
            var document = try readUnlocked()
            let candidates = document.records.filter { $0.destinationPath == resolved.url.path }
            for record in candidates {
                let stage = resolved.url.appendingPathComponent(record.stageName)
                if FileManager.default.fileExists(atPath: stage.path) {
                    try FileManager.default.removeItem(at: stage)
                    try MacDestinationBookmarkStore.syncDirectory(resolved.url)
                }
                document.records.removeAll { $0.id == record.id }
            }
            try writeUnlocked(document)
        }
    }

    private func readUnlocked() throws -> Document {
        guard FileManager.default.fileExists(atPath: url.path) else { return Document(schema: 1, records: []) }
        let document = try JSONDecoder().decode(Document.self, from: Data(contentsOf: url))
        guard document.schema == 1 else { throw MacDestinationError.storage("journal_schema") }
        return document
    }

    private func writeUnlocked(_ document: Document) throws {
        try JSONEncoder().encode(document).write(to: url, options: [.atomic])
        try MacDestinationBookmarkStore.syncFile(url)
        try MacDestinationBookmarkStore.syncDirectory(url.deletingLastPathComponent())
    }
}

private final class MacDestinationScope: @unchecked Sendable {
    let resolved: MacDestinationBookmarkStore.Resolved
    private let lock = NSLock()
    private var active: Bool

    init(resolved: MacDestinationBookmarkStore.Resolved) throws {
        self.resolved = resolved
        active = resolved.url.startAccessingSecurityScopedResource()
        guard active else { throw MacDestinationError.permissionRequired }
    }

    func close() {
        lock.withLock {
            guard active else { return }
            active = false
            resolved.url.stopAccessingSecurityScopedResource()
        }
    }

    deinit { close() }
}

actor MacReceiveDestination: InboundTransferDestination {
    private let bookmarks: MacDestinationBookmarkStore
    private let journal: MacStagingJournal

    fileprivate init(
        bookmarks: MacDestinationBookmarkStore = .shared,
        journal: MacStagingJournal = .shared
    ) {
        self.bookmarks = bookmarks
        self.journal = journal
    }

    init(bookmarks: MacDestinationBookmarkStore = .shared) {
        self.bookmarks = bookmarks
        journal = .shared
    }

    func preflight() throws {
        let resolved = try bookmarks.resolve()
        let scope = try MacDestinationScope(resolved: resolved)
        defer { scope.close() }
        try journal.reconcile(resolved)
        let probe = resolved.url.appendingPathComponent(".swiftshare-preflight-\(UUID().uuidString)")
        let descriptor = Darwin.open(probe.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        guard descriptor >= 0 else { throw MacDestinationError.permissionRequired }
        Darwin.close(descriptor)
        try FileManager.default.removeItem(at: probe)
        try MacDestinationBookmarkStore.syncDirectory(resolved.url)
    }

    func reserve(
        displayName: String,
        mediaType: String,
        sessionID: UUID,
        itemID: UUID
    ) throws -> any InboundTransferReservation {
        try InboundTransferSession.validateBasename(displayName)
        let resolved = try bookmarks.resolve()
        let scope = try MacDestinationScope(resolved: resolved)
        let id = UUID()
        let stageName = ".swiftshare-stage-\(sessionID.uuidString)-\(itemID.uuidString)-\(id.uuidString)"
        var record = MacStageRecord(
            id: id,
            destinationPath: resolved.url.path,
            bookmark: resolved.bookmark,
            stageName: stageName,
            finalName: nil,
            phase: .planned
        )
        do {
            try journal.upsert(record)
            let stageURL = resolved.url.appendingPathComponent(stageName)
            let descriptor = Darwin.open(
                stageURL.path,
                O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW,
                S_IRUSR | S_IWUSR
            )
            guard descriptor >= 0 else { throw MacDestinationError.storage("stage_create_failed") }
            let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
            try MacDestinationBookmarkStore.syncDirectory(resolved.url)
            record.phase = .staged
            try journal.upsert(record)
            return MacReceiveReservation(
                requestedName: displayName,
                stageURL: stageURL,
                handle: handle,
                scope: scope,
                record: record,
                journal: journal
            )
        } catch {
            scope.close()
            try? journal.remove(id)
            throw error
        }
    }
}

actor MacReceiveReservation: InboundTransferReservation {
    let displayName: String
    private let stageURL: URL
    private var handle: FileHandle?
    private let scope: MacDestinationScope
    private var record: MacStageRecord
    private let journal: MacStagingJournal
    private var committed = false

    fileprivate init(
        requestedName: String,
        stageURL: URL,
        handle: FileHandle,
        scope: MacDestinationScope,
        record: MacStageRecord,
        journal: MacStagingJournal
    ) {
        displayName = requestedName
        self.stageURL = stageURL
        self.handle = handle
        self.scope = scope
        self.record = record
        self.journal = journal
    }

    func write(_ data: Data) throws {
        guard let handle else { throw MacDestinationError.storage("stage_closed") }
        try handle.write(contentsOf: data)
    }

    func finishWriting() throws {
        guard let value = handle else { return }
        try value.synchronize()
        try value.close()
        handle = nil
    }

    func commit() throws -> CommittedArtifact {
        try finishWriting()
        for suffix in 0..<10_000 {
            let candidate = try Self.candidate(displayName, suffix: suffix)
            record.phase = .committing
            record.finalName = candidate
            try journal.upsert(record)
            let destination = scope.resolved.url.appendingPathComponent(candidate)
            let result = stageURL.path.withCString { source in
                destination.path.withCString { target in
                    Darwin.renameatx_np(AT_FDCWD, source, AT_FDCWD, target, UInt32(RENAME_EXCL))
                }
            }
            if result == 0 {
                committed = true
                try MacDestinationBookmarkStore.syncDirectory(scope.resolved.url)
                try journal.remove(record.id)
                scope.close()
                return CommittedArtifact(id: destination.path)
            }
            guard errno == EEXIST else {
                throw MacDestinationError.storage("destination_commit_failed_\(errno)")
            }
        }
        throw MacDestinationError.storage("name_exhausted")
    }

    func abort() {
        guard !committed else { return }
        try? handle?.close()
        handle = nil
        do {
            if FileManager.default.fileExists(atPath: stageURL.path) {
                try FileManager.default.removeItem(at: stageURL)
                try MacDestinationBookmarkStore.syncDirectory(scope.resolved.url)
            }
            try journal.remove(record.id)
        } catch {
            // Preserve the durable journal on cleanup failure for the next reconciliation.
        }
        scope.close()
    }

    private static func candidate(_ original: String, suffix: Int) throws -> String {
        do { return try DeterministicDestinationName.candidate(original: original, suffix: suffix) }
        catch { throw MacDestinationError.invalidName }
    }
}
