package com.sai.sports.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Sprint 20: a release build cannot send anything in the clear, and cannot be
 * pointed at another server.
 */
class TransportSecurityTest {

    @Test
    fun `a release build ignores a stored server address`() {
        val url = SyncConfig.resolve(
            allowCustom = false,
            releaseUrl = "https://api.example.gov.in",
            stored = "http://192.168.1.5:8010",
            developerDefault = "http://127.0.0.1:8010"
        )
        assertEquals("https://api.example.gov.in", url)
    }

    @Test
    fun `a debug build can be pointed at a developer's server`() {
        assertEquals(
            "http://192.168.1.5:8010",
            SyncConfig.resolve(true, "https://api.example.gov.in", "http://192.168.1.5:8010", "http://127.0.0.1:8010")
        )
        assertEquals(
            "http://127.0.0.1:8010",
            SyncConfig.resolve(true, "https://api.example.gov.in", null, "http://127.0.0.1:8010")
        )
    }

    @Test
    fun `the release network config refuses plain HTTP and user certificates`() {
        val config = parse(File("src/main/res/xml/network_security_config.xml"))
        val base = config.getElementsByTagName("base-config").item(0)
        assertEquals("false", base.attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
        val sources = (0 until config.getElementsByTagName("certificates").length).map {
            config.getElementsByTagName("certificates").item(it).attributes.getNamedItem("src").nodeValue
        }
        assertEquals(listOf("system"), sources)
        // No domain may be carved out of the rule.
        assertEquals(0, config.getElementsByTagName("domain-config").length)
    }

    @Test
    fun `the manifest uses the network config and does not allow cleartext itself`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse(manifest.contains("usesCleartextTraffic=\"true\""))
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
}
