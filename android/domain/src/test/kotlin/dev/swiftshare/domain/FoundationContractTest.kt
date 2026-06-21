package dev.swiftshare.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoundationContractTest {
    @Test
    fun `Kotlin decodes the canonical foundation fixture`() {
        val resource = requireNotNull(javaClass.classLoader?.getResource("foundation-v1.json"))
        val json = JSONObject(resource.readText())
        val protocol = json.getJSONObject("protocol_version")
        val capabilities = json.getJSONArray("capabilities")
        val terminalResult = json.getJSONObject("terminal_result")

        val contract = FoundationContract(
            fixtureVersion = json.getInt("fixture_version"),
            protocolVersion = ProtocolVersion(
                major = protocol.getInt("major"),
                minor = protocol.getInt("minor"),
            ),
            capabilities = List(capabilities.length()) { index ->
                Capability(capabilities.getString(index))
            },
            terminalResult = TransferTerminalResult.entries.single {
                it.contractName == terminalResult.getString("status")
            },
        )

        assertEquals(1, contract.fixtureVersion)
        assertEquals(ProtocolVersion(major = 1, minor = 0), contract.protocolVersion)
        assertEquals(listOf(Capability.FILE_TRANSFER), contract.capabilities)
        assertEquals(TransferTerminalResult.COMPLETED, contract.terminalResult)
        assertTrue(contract.capabilities.isNotEmpty())
        assertEquals(Capability("future_optional"), Capability("future_optional"))
    }
}
