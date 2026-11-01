package dev.raggedlib.extract

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/** Not a unit test: per-page TOC evidence for RAGGEDLIB_TOC_DEBUG=<pdf>, first 30 pages. */
class TocDebug {
    @Test
    fun debug() {
        val path = System.getenv("RAGGEDLIB_TOC_DEBUG")
        assumeTrue(path != null)
        val out = File.createTempFile("tocdebug", ".txt")
        ProcessBuilder("pdftotext", "-enc", "UTF-8", path, out.path).start().waitFor(120, TimeUnit.SECONDS)
        val all = PdfText.paragraphs(out.readText(), keepJunk = true); out.delete()
        Structure.tocPageStats(all).take(30).forEach { println("TOCDBG $it") }
        all.filter { (it.page ?: 0) in (System.getenv("RAGGEDLIB_TOC_PAGES") ?: "9-10").split("-").let { it[0].toInt()..it[1].toInt() } }.forEach { p ->
            println("PARA p${p.page} prose=${Regex("\\p{L}[.!?]\\s+\\p{Lu}").find(p.text)?.value} ${p.text.take(160)}")
        }
    }
}
