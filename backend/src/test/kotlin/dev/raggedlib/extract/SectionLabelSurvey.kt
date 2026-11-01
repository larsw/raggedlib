package dev.raggedlib.extract

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Not a unit test: compares section labels before/after TOC detection on real PDFs. Runs only with
 * RAGGEDLIB_SURVEY_PDFS=<file with one PDF path per line>; prints per-book stats and sample label runs.
 */
class SectionLabelSurvey {
    /** Old labelling; also returns, per paragraph, the page whose heading set its label. */
    private fun oldInferWithOrigin(paragraphs: List<Paragraph>): Pair<List<Paragraph>, List<Int?>> {
        var current: String? = null
        var origin: Int? = null
        val origins = ArrayList<Int?>()
        val out = paragraphs.map { p ->
            Structure.headingOf(p.text)?.let { current = it.take(200); origin = p.page }
            origins += if (current != null) origin else null
            if (current != null) p.copy(section = current) else p
        }
        return out to origins
    }

    private fun oldInfer(paragraphs: List<Paragraph>) = oldInferWithOrigin(paragraphs).first

    @Test
    fun survey() {
        val list = System.getenv("RAGGEDLIB_SURVEY_PDFS")
        assumeTrue(list != null)
        val chunker = Chunker(1500, 2000, 200, 80)
        var books = 0; var changedBooks = 0; var chunksTotal = 0; var changedChunks = 0; var oldLabeled = 0; var newLabeled = 0
        var oldFront = 0; var newFront = 0; var lostLaterTotal = 0; var lostGoodTotal = 0
        File(list!!).readLines().filter { it.isNotBlank() }.forEach { path ->
            val out = File.createTempFile("survey", ".txt")
            val p = ProcessBuilder("pdftotext", "-enc", "UTF-8", path, out.path).redirectErrorStream(true).start()
            if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) { out.delete(); return@forEach }
            val raw = out.readText(); out.delete()
            if (raw.count { it.isLetter() } < 500) return@forEach
            val all = PdfText.paragraphs(raw, keepJunk = true)
            val tocP = Structure.tocPages(all)
            val paras = Structure.mergeBroken(all.filterNot { TextCleaner.isJunkParagraph(it.text) }, 8000)
            val old = chunker.chunk(oldInfer(paras))
            val new = chunker.chunk(Structure.inferSections(paras, tocP))
            val pages = paras.maxOf { it.page ?: 0 }.coerceAtLeast(1)
            // "front matter labelled as a late chapter": first 8% of pages, label names chapter/part >= 3
            val late = Regex("^(chapter|part)\\s+([3-9]|[1-9][0-9])\\b", RegexOption.IGNORE_CASE)
            fun front(cs: List<Chunk>) = cs.count { (it.page ?: 0) <= pages * 8 / 100 && it.section?.let(late::containsMatchIn) == true }
            books++; chunksTotal += new.size
            oldLabeled += old.count { it.section != null }; newLabeled += new.count { it.section != null }
            oldFront += front(old); newFront += front(new)
            if (front(new) > 0) println("   FL ${File(path).name.take(50)}: ${front(old)} -> ${front(new)} " +
                new.filter { (it.page ?: 0) <= pages * 8 / 100 && it.section?.let(late::containsMatchIn) == true }
                    .map { "p${it.page} ${it.section!!.take(40)}" }.distinct().take(3))
            // paragraph level: chunk boundaries move with section changes
            val oldP = oldInfer(paras); val newP = Structure.inferSections(paras, tocP)
            val frontEnd = maxOf(40, pages * 15 / 100)
            val diff = oldP.indices.count { oldP[it].section != newP[it].section }
            val lostLater = oldP.indices.count { (paras[it].page ?: 0) > frontEnd && oldP[it].section != null && newP[it].section == null }
            lostLaterTotal += lostLater
            // real labels lost: the old label came from a heading on a page that isn't contents
            val allToc = tocP + Structure.tocPages(paras)
            val origins = oldInferWithOrigin(paras).second
            val lostGood = oldP.indices.count { oldP[it].section != null && newP[it].section == null && origins[it] !in allToc }
            lostGoodTotal += lostGood
            if (lostGood > 0) println("   LOSTGOOD ${File(path).name.take(50)}: $lostGood paras, e.g. " +
                oldP.indices.filter { oldP[it].section != null && newP[it].section == null && origins[it] !in allToc }
                    .groupingBy { "p${origins[it]} ${oldP[it].section!!.take(50)}" }.eachCount().entries.sortedByDescending { it.value }.take(3)
                    .joinToString(" ; ") { "${it.key} x${it.value}" } + " | toc=${allToc.sorted().take(15)}")
            if (lostLater > 0) {
                val lost = oldP.indices.filter { (paras[it].page ?: 0) > frontEnd && oldP[it].section != null && newP[it].section == null }
                    .groupingBy { oldP[it].section!!.take(60) }.eachCount().entries.sortedByDescending { it.value }.take(4)
                println("   LOST ${File(path).name.take(40)}: " + lost.joinToString(" ; ") { "${it.key} x${it.value}" })
            }
            changedChunks += diff
            if (diff > 0) {
                changedBooks++
                val toc = tocP + Structure.tocPages(paras)
                println("== ${File(path).name.take(70)} | pages $pages | toc pages ${toc.sorted().take(12)} | changed paras $diff/${paras.size} lostLater $lostLater | front-late ${front(old)} -> ${front(new)}")
                new.take(12).forEachIndexed { i, c ->
                    val o = old.getOrNull(i)
                    println("   p${c.page} old=[${o?.section?.take(45)}] new=[${c.section?.take(45)}]")
                }
            }
        }
        println("SUMMARY books=$books changedBooks=$changedBooks chunks=$chunksTotal changedChunks=$changedChunks " +
            "labeled old=$oldLabeled new=$newLabeled frontLateLabels old=$oldFront new=$newFront lostLabelsAfterFront=$lostLaterTotal lostGood=$lostGoodTotal")
    }
}
