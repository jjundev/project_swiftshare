package dev.swiftshare.domain

data class FoundationContract(
    val fixtureVersion: Int,
    val protocolVersion: ProtocolVersion,
    val capabilities: List<Capability>,
    val terminalResult: TransferTerminalResult,
)

data class ProtocolVersion(
    val major: Int,
    val minor: Int,
)

data class Capability(val contractName: String) {
    init { require(Regex("[a-z][a-z0-9_.-]{0,63}").matches(contractName)) }
    companion object { val FILE_TRANSFER = Capability("file_transfer") }
}

enum class TransferTerminalResult(val contractName: String) {
    COMPLETED("completed"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
    FAILED("failed"),
}
