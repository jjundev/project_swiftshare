import Foundation

public struct FoundationContract: Codable, Equatable, Sendable {
    public let fixtureVersion: Int
    public let protocolVersion: ProtocolVersion
    public let capabilities: [Capability]
    public let terminalResult: TerminalResultFixture

    public init(
        fixtureVersion: Int,
        protocolVersion: ProtocolVersion,
        capabilities: [Capability],
        terminalResult: TerminalResultFixture
    ) {
        self.fixtureVersion = fixtureVersion
        self.protocolVersion = protocolVersion
        self.capabilities = capabilities
        self.terminalResult = terminalResult
    }

    private enum CodingKeys: String, CodingKey {
        case fixtureVersion = "fixture_version"
        case protocolVersion = "protocol_version"
        case capabilities
        case terminalResult = "terminal_result"
    }
}

public struct ProtocolVersion: Codable, Equatable, Sendable {
    public let major: Int
    public let minor: Int

    public init(major: Int, minor: Int) {
        self.major = major
        self.minor = minor
    }
}

public struct Capability: RawRepresentable, Codable, Equatable, Sendable {
    public let rawValue: String

    public init?(rawValue: String) {
        guard rawValue.range(of: #"^[a-z][a-z0-9_.-]{0,63}$"#, options: .regularExpression) != nil else {
            return nil
        }
        self.rawValue = rawValue
    }

    public init(from decoder: any Decoder) throws {
        let value = try decoder.singleValueContainer().decode(String.self)
        guard let capability = Self(rawValue: value) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid capability"))
        }
        self = capability
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }

    public static let fileTransfer = Capability(rawValue: "file_transfer")!
}

public struct TerminalResultFixture: Codable, Equatable, Sendable {
    public let status: TransferTerminalResult

    public init(status: TransferTerminalResult) {
        self.status = status
    }
}

public enum ProductBoundary: Sendable {
    public static let title = "SwiftShare"
    public static let message = "Install SwiftShare on both your Mac and Android device."
    public static let detail = "SwiftShare transfers directly over your local network. It does not use an account, cloud service, Quick Share, or AirDrop."
}
