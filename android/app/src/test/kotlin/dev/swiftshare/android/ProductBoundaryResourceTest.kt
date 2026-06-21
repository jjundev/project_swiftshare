package dev.swiftshare.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductBoundaryResourceTest {
    @Test
    fun `English product boundary explains both installs and local independence`() {
        val appDir = File(requireNotNull(System.getProperty("swiftshare.appDir")))
        val strings = File(appDir, "src/main/res/values/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings)
        val copy = buildString {
            val nodes = document.getElementsByTagName("string")
            repeat(nodes.length) { index ->
                append(nodes.item(index).textContent)
                append('\n')
            }
        }

        assertTrue(copy.contains("Mac"))
        assertTrue(copy.contains("Android"))
        assertTrue(copy.contains("local network"))
        assertTrue(copy.contains("Quick Share"))
        assertTrue(copy.contains("AirDrop"))
    }
}

