package dev.raggedlib.extract

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Not a unit test: section labels with and without page-numbered running-head removal, on the PDFs listed in
 * RAGGEDLIB_SURVEY_PDFS. "Folio labels" are labels like "4 Part 1 A Bit of Background" whose text recurs with
 * other numbers; "other changes" are paragraphs whose label changed for any other reason (should be rare).
 */
class RunningHeadSurvey {
    private fun labels(raw: String, heads: Boolean): List<Paragraph> {
        val all = PdfText.paragraphs(raw, keepJunk = true, runningHeads = heads)
        val toc = Structure.tocPages(all)
        return Structure.inferSections(Structure.mergeBroken(all.filterNot { TextCleaner.isJunkParagraph(it.text) }, 8000), toc)
    }

    private val numbered = Regex("^(\\d{1,4})\\s+(.+)$")

    private fun folioLabels(ps: List<Paragraph>): Set<String> {
        val byText = ps.mapNotNull { it.section }.toSet().mapNotNull { l -> numbered.matchEntire(l)?.let { l to it.groupValues[2] } }
            .groupBy({ it.second }, { it.first })
        return byText.filterValues { it.size >= 2 }.values.flatten().toSet()
    }

    @Test
    fun survey() {
        val list = System.getenv("RAGGEDLIB_SURVEY_PDFS")
        assumeTrue(list != null)
        var books = 0; var folioOld = 0; var folioNew = 0; var other = 0; var labeledOld = 0; var labeledNew = 0; var paras = 0
        File(list!!).readLines().filter { it.isNotBlank() }.forEach { path ->
            val out = File.createTempFile("survey", ".txt")
            val p = ProcessBuilder("pdftotext", "-enc", "UTF-8", path, out.path).redirectErrorStream(true).start()
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) { out.delete(); return@forEach }
            val raw = out.readText(); out.delete()
            if (raw.count { it.isLetter() } < 500) return@forEach
            val old = labels(raw, false); val new = labels(raw, true)
            books++; paras += new.size
            labeledOld += old.count { it.section != null }; labeledNew += new.count { it.section != null }
            val fo = folioLabels(old); val fn = folioLabels(new)
            val foCount = old.count { it.section in fo }; val fnCount = new.count { it.section in fn }
            folioOld += foCount; folioNew += fnCount
            // other changes: compare by text, skipping paragraphs whose old label was a folio label
            val newByText = new.groupBy { it.text }.mapValues { (_, v) -> v.map { it.section } }
            val changed = old.filter { it.section !in fo }.filter { o ->
                val n = newByText[o.text] ?: return@filter false
                o.section !in n
            }
            other += changed.size
            if (foCount > 0 || fnCount > 0 || changed.isNotEmpty()) {
                println("== ${File(path).name.take(60)} | folio labels ${fo.size} (${foCount} paras) -> ${fn.size} (${fnCount}) | other changes ${changed.size}")
                fo.take(3).forEach { println("   was: $it") }
                changed.take(3).forEach { o -> println("   OTHER p${o.page}: [${o.section?.take(50)}] -> ${newByText[o.text]?.map { it?.take(50) }?.distinct()}") }
            }
        }
        println("SUMMARY books=$books paras=$paras folioParas old=$folioOld new=$folioNew otherChanged=$other labeled old=$labeledOld new=$labeledNew")
    }
}
