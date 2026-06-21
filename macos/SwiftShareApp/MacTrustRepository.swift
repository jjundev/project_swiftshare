import CryptoKit
import Foundation
import SwiftShareDomain

struct MacStoredPeer: Codable, Identifiable, Equatable, Sendable {
    let keyIDHex: String
    let canonicalSPKIBase64: String
    let displayName: String
    let platform: String
    let pairedAt: Date
    var approvalPolicy: PeerApprovalPolicy
    var id: String { keyIDHex }
    var canonicalSPKI: Data { Data(base64Encoded: canonicalSPKIBase64) ?? Data() }

    private enum CodingKeys: String, CodingKey {
        case keyIDHex, canonicalSPKIBase64, displayName, platform, pairedAt, approvalPolicy
    }

    init(
        keyIDHex: String,
        canonicalSPKIBase64: String,
        displayName: String,
        platform: String,
        pairedAt: Date,
        approvalPolicy: PeerApprovalPolicy = .requireApproval
    ) {
        self.keyIDHex = keyIDHex
        self.canonicalSPKIBase64 = canonicalSPKIBase64
        self.displayName = displayName
        self.platform = platform
        self.pairedAt = pairedAt
        self.approvalPolicy = approvalPolicy
    }

    init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        keyIDHex = try values.decode(String.self, forKey: .keyIDHex)
        canonicalSPKIBase64 = try values.decode(String.self, forKey: .canonicalSPKIBase64)
        displayName = try values.decode(String.self, forKey: .displayName)
        platform = try values.decode(String.self, forKey: .platform)
        pairedAt = try values.decode(Date.self, forKey: .pairedAt)
        approvalPolicy = try values.decodeIfPresent(PeerApprovalPolicy.self, forKey: .approvalPolicy) ?? .requireApproval
    }
}

actor MacTrustRepository {
    private struct Document: Codable {
        let schema: Int
        var trustGeneration: UInt64
        var policyRevision: UInt64
        var peers: [MacStoredPeer]

        private enum CodingKeys: String, CodingKey { case schema, trustGeneration, policyRevision, generation, peers }

        init(schema: Int = 2, trustGeneration: UInt64, policyRevision: UInt64, peers: [MacStoredPeer]) {
            self.schema = schema
            self.trustGeneration = trustGeneration
            self.policyRevision = policyRevision
            self.peers = peers
        }

        init(from decoder: any Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            schema = try values.decode(Int.self, forKey: .schema)
            peers = try values.decode([MacStoredPeer].self, forKey: .peers)
            trustGeneration = try values.decodeIfPresent(UInt64.self, forKey: .trustGeneration)
                ?? values.decodeIfPresent(UInt64.self, forKey: .generation)
                ?? (peers.isEmpty ? 0 : 1)
            policyRevision = try values.decodeIfPresent(UInt64.self, forKey: .policyRevision) ?? 0
        }

        func encode(to encoder: any Encoder) throws {
            var values = encoder.container(keyedBy: CodingKeys.self)
            try values.encode(schema, forKey: .schema)
            try values.encode(trustGeneration, forKey: .trustGeneration)
            try values.encode(policyRevision, forKey: .policyRevision)
            try values.encode(peers, forKey: .peers)
        }
    }

    static let shared = MacTrustRepository()
    private let url: URL

    init(url: URL? = nil) {
        if let url { self.url = url }
        else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("SwiftShare", isDirectory: true)
            try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
            self.url = base.appendingPathComponent("paired-peers-v1.json")
        }
    }

    func peers() throws -> [MacStoredPeer] { try read().peers.sorted { $0.displayName < $1.displayName } }
    func peer(id: String) throws -> MacStoredPeer? { try read().peers.first { $0.keyIDHex == id } }

    @discardableResult
    func upsert(_ descriptor: PairingDeviceDescriptor, now: Date = Date()) throws -> MacStoredPeer {
        var document = try read()
        let id = descriptor.keyID.map { String(format: "%02x", $0) }.joined()
        let prior = document.peers.first { $0.keyIDHex == id }
        document.peers.removeAll { $0.keyIDHex == id }
        let record = MacStoredPeer(
            keyIDHex: id,
            canonicalSPKIBase64: descriptor.canonicalSPKI.base64EncodedString(),
            displayName: descriptor.displayName,
            platform: descriptor.platform,
            pairedAt: prior?.pairedAt ?? now,
            approvalPolicy: prior?.approvalPolicy ?? .requireApproval
        )
        document.peers.append(record)
        if prior == nil || prior?.canonicalSPKI != descriptor.canonicalSPKI { document.trustGeneration += 1 }
        try write(document)
        return record
    }

    @discardableResult
    func setApprovalPolicy(id: String, policy: PeerApprovalPolicy) throws -> MacStoredPeer {
        var document = try read()
        guard let index = document.peers.firstIndex(where: { $0.keyIDHex == id }) else { throw PairingError.storage }
        if document.peers[index].approvalPolicy == policy { return document.peers[index] }
        document.peers[index].approvalPolicy = policy
        document.policyRevision += 1
        try write(document)
        return document.peers[index]
    }

    @discardableResult
    func remove(id: String) throws -> Bool {
        var document = try read()
        let before = document.peers.count
        document.peers.removeAll { $0.keyIDHex == id }
        guard before != document.peers.count else { return false }
        document.trustGeneration += 1
        document.policyRevision += 1
        try write(document)
        return true
    }

    func clear() throws {
        var document = try read()
        guard !document.peers.isEmpty else { return }
        document.peers = []
        document.trustGeneration += 1
        document.policyRevision += 1
        try write(document)
    }

    private func read() throws -> Document {
        guard FileManager.default.fileExists(atPath: url.path) else {
            return Document(trustGeneration: 0, policyRevision: 0, peers: [])
        }
        let decoded = try JSONDecoder().decode(Document.self, from: Data(contentsOf: url))
        guard decoded.schema == 1 || decoded.schema == 2 else { throw PairingError.storage }
        var validated = decoded
        for peer in decoded.peers {
            guard let spki = Data(base64Encoded: peer.canonicalSPKIBase64), !spki.isEmpty else { throw PairingError.storage }
            let descriptor = try PairingDeviceDescriptor(
                canonicalSPKI: spki,
                displayName: peer.displayName,
                platform: peer.platform
            )
            guard descriptor.keyID.map({ String(format: "%02x", $0) }).joined() == peer.keyIDHex else {
                throw PairingError.storage
            }
        }
        if decoded.schema == 1 {
            validated = Document(trustGeneration: decoded.trustGeneration, policyRevision: 0, peers: decoded.peers)
            try write(validated)
        }
        return validated
    }

    private func write(_ document: Document) throws {
        let data = try JSONEncoder().encode(document)
        let temporary = url.appendingPathExtension("tmp")
        try data.write(to: temporary, options: [.atomic, .completeFileProtection])
        if FileManager.default.fileExists(atPath: url.path) {
            _ = try FileManager.default.replaceItemAt(url, withItemAt: temporary)
        } else {
            try FileManager.default.moveItem(at: temporary, to: url)
        }
    }
}
