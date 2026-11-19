package dev.raggedlib.calibre

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class CalibreBookIdentityTest {
    @Test
    fun `reads title and authors from OPF and ignores non-author creators`() {
        val opf = """
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>  A Book  </dc:title>
                <dc:creator opf:role="aut">Ann Author</dc:creator>
                <dc:creator opf:role="edt">Editor Name</dc:creator>
                <dc:creator>Another Author</dc:creator>
              </metadata>
            </package>
        """.trimIndent()

        assertEquals(
            CalibreBookIdentity("A Book", listOf("Ann Author", "Another Author")),
            parseCalibreBookIdentity(opf, "fallback"),
        )
    }

    @Test
    fun `uses filename title and Unknown author when metadata is missing`() {
        val opf = """<package xmlns="http://www.idpf.org/2007/opf"><metadata/></package>"""
        assertEquals(CalibreBookIdentity("Filename", listOf("Unknown")), parseCalibreBookIdentity(opf, "Filename"))
    }

    @Test
    fun `rejects external entity declarations`() {
        val opf = """<!DOCTYPE package [<!ENTITY x SYSTEM "file:///etc/passwd">]><package><metadata><title>&x;</title></metadata></package>"""
        assertFails { parseCalibreBookIdentity(opf, "fallback") }
    }
}
