package dev.raggedlib.extract

/** Text Tesseract recognised on one page; [confidence] is its mean word confidence (0–100), weighted by word length. */
data class OcrPage(val text: String, val confidence: Double)

/** Parsing and decisions around OCR (see [PdfOcr]); no external processes, so they are unit-tested. */
object OcrText {

    /** `tesseract --list-langs`: a header line, then one language per line (`osd`, orientation detection, among them). */
    fun installedLanguages(listLangs: String): Set<String> =
        listLangs.lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() && !it.contains(' ') }.toSet()

    /** Calibre stores ISO 639 codes (eng, nob, deu); Tesseract's traineddata names mostly match them. */
    fun tesseractCode(calibreLanguage: String): String = when (val code = calibreLanguage.trim().lowercase()) {
        "nob", "nno", "no", "nb", "nn" -> "nor"
        "zho", "chi" -> "chi_sim"
        "en" -> "eng"
        "de", "ger" -> "deu"
        "fr", "fre" -> "fra"
        "es" -> "spa"
        "it" -> "ita"
        "sv" -> "swe"
        "da" -> "dan"
        "nl", "dut" -> "nld"
        else -> code
    }

    /**
     * Tesseract's `-l` for a book: its Calibre languages, then the [configured] ones (`eng+nor`), in that order and
     * as far as they are installed. Both, because Calibre's language is often missing or a default, and a second
     * language costs little: `eng+nor` reads English as well as `eng` alone. Null if none of them is installed.
     */
    fun languages(bookLanguages: List<String>, configured: String, installed: Set<String>): String? {
        val wanted = bookLanguages.map(::tesseractCode) + configured.split('+', ',', ' ').map { it.trim() }
        return wanted.filter { it.isNotEmpty() && it != "osd" && it in installed }.distinct().joinToString("+").ifEmpty { null }
    }

    /**
     * Mean word confidence from Tesseract's TSV output, weighted by the letters in each word, so that a page of
     * running text scores high and one of stray marks read off a photo or diagram scores low. 0 without words.
     */
    fun confidence(tsv: String): Double {
        var weighted = 0.0
        var letters = 0
        for (line in tsv.lineSequence().drop(1)) {
            val cols = line.split('\t')
            if (cols.size < 12 || cols[0] != "5") continue // level 5 = word
            val conf = cols[10].toDoubleOrNull() ?: continue
            if (conf < 0) continue
            val n = cols[11].count { it.isLetterOrDigit() }
            weighted += conf * n
            letters += n
        }
        return if (letters == 0) 0.0 else weighted / letters
    }

    /** Page sizes in points from `pdfinfo -f 1 -l N`: lines like `Page    3 size: 595.32 x 841.92 pts (A4)`. */
    fun pageSizes(pdfinfo: String): Map<Int, Pair<Double, Double>> =
        PAGE_SIZE.findAll(pdfinfo).associate { m ->
            m.groupValues[1].toInt() to (m.groupValues[2].toDouble() to m.groupValues[3].toDouble())
        }

    /**
     * Share of each page's area covered by raster images (capped at 1), from `pdfimages -list`. A scanned page is one
     * big image; a page of a born-digital PDF holds at most a figure. Images are placed at width/x-ppi inches.
     */
    fun imageCoverage(pdfimagesList: String, pageSizes: Map<Int, Pair<Double, Double>>): Map<Int, Double> {
        val area = HashMap<Int, Double>()
        for (line in pdfimagesList.lineSequence().drop(2)) {
            val cols = line.trim().split(WHITESPACE)
            // page num type width height color comp bpc enc interp object-id(2 columns) x-ppi y-ppi size ratio
            if (cols.size < 14 || cols[2] != "image") continue
            val page = cols[0].toIntOrNull() ?: continue
            val w = cols[3].toDoubleOrNull() ?: continue
            val h = cols[4].toDoubleOrNull() ?: continue
            val xppi = cols[12].toDoubleOrNull()?.takeIf { it > 0 } ?: continue
            val yppi = cols[13].toDoubleOrNull()?.takeIf { it > 0 } ?: continue
            area.merge(page, (w / xppi * 72) * (h / yppi * 72), Double::plus)
        }
        return area.mapNotNull { (page, a) ->
            val (pw, ph) = pageSizes[page] ?: return@mapNotNull null
            if (pw <= 0 || ph <= 0) null else page to (a / (pw * ph)).coerceAtMost(1.0)
        }.toMap()
    }

    /**
     * [pages] (sorted or not) as at most [max] ranges: runs of consecutive pages, then the closest runs merged (which
     * adds the pages between them) until few enough are left.
     */
    fun ranges(pages: Collection<Int>, max: Int): List<IntRange> {
        val runs = ArrayList<IntRange>()
        for (p in pages.toSortedSet()) {
            val last = runs.lastOrNull()
            if (last != null && p == last.last + 1) runs[runs.size - 1] = last.first..p else runs += p..p
        }
        while (runs.size > max.coerceAtLeast(1)) {
            val i = (0 until runs.size - 1).minBy { runs[it + 1].first - runs[it].last }
            runs[i] = runs[i].first..runs[i + 1].last
            runs.removeAt(i + 1)
        }
        return runs
    }

    /** Whether pdftotext found a text layer: enough letters in all, and per page on average. */
    fun hasTextLayer(lettersPerPage: List<Int>, minLettersPerPage: Int): Boolean {
        val letters = lettersPerPage.sum()
        return letters >= MIN_LETTERS && letters / lettersPerPage.size.coerceAtLeast(1) >= minLettersPerPage
    }

    /**
     * 1-based pages to OCR. Without a text layer, every page short of text (a scan, or text drawn as outlines). With
     * one, only pages short of text that are mostly a raster image ([coverage]): scanned pages bound into a
     * born-digital PDF, not its blank pages, vector diagrams and figure plates.
     */
    fun pagesToOcr(
        lettersPerPage: List<Int>,
        minLettersPerPage: Int,
        hasTextLayer: Boolean,
        coverage: Map<Int, Double>,
        minCoverage: Double,
    ): List<Int> = lettersPerPage.withIndex()
        .filter { (i, letters) -> letters < minLettersPerPage && (!hasTextLayer || (coverage[i + 1] ?: 0.0) >= minCoverage) }
        .map { it.index + 1 }

    /** A book needs this many letters in all to be worth indexing. */
    const val MIN_LETTERS = 500

    private val PAGE_SIZE = Regex("""(?m)^Page\s+(\d+)\s+size:\s+([\d.]+)\s+x\s+([\d.]+)\s+pts""")
    private val WHITESPACE = Regex("\\s+")
}
