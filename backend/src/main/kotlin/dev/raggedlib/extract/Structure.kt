package dev.raggedlib.extract

/** Format-independent post-processing of extracted paragraphs. */
object Structure {
    private val chapterHeading = Regex("^(chapter|part|appendix|section|lesson)\\s+([0-9]{1,3}|[ivxlc]{1,6}|[a-z])\\b.{0,100}$", RegexOption.IGNORE_CASE)
    private val numberedHeading = Regex("^\\d{1,2}(\\.\\d{1,2}){0,3}\\.?\\s+\\p{Lu}[^\\n]{1,90}$")
    private val sentenceEnd = Regex("[.!?:;\"'”’)\\]]$")

    /**
     * Heuristic heading for documents without markup: "Chapter 3: Foo", "3.2 Bar". Single short line,
     * not ending like a sentence, no trailing page number.
     */
    fun headingOf(text: String): String? {
        val t = unspaced(text.trim())
        if (t.length < 3 || t.length > 110 || '\n' in t) return null
        if (t.endsWith('.') || t.endsWith(',') || t.endsWith(';')) return null
        if (!chapterHeading.matches(t) && !numberedHeading.matches(t)) return null
        if (Regex("\\s\\d{1,4}$").containsMatchIn(t) && !chapterHeading.matches(t)) return null // TOC line "3.2 Foo 45"
        // "Chapter 10: Tools 73" -> "Chapter 10: Tools", but "Chapter 3" keeps its number
        val stripped = t.replace(Regex("\\s+\\d{1,4}$"), "").trim()
        return if (bareKeyword.matches(stripped)) t else stripped
    }

    private val pageNumbers = Regex("^(?:[0-9]{1,4}|[ivxlc]{1,7})(?:\\s+(?:[0-9]{1,4}|[ivxlc]{1,7})){2,}$", RegexOption.IGNORE_CASE)
    private val bareKeyword = Regex("^(chapter|part|appendix|section|lesson)$", RegexOption.IGNORE_CASE)
    private val bareChapter = Regex("^(chapter|part|appendix|lesson)\\s+([0-9]{1,3}|[ivxlc]{1,6}|[a-z])$", RegexOption.IGNORE_CASE)
    private val letterSpaced = Regex("^((?:\\p{L}\\s){2,}\\p{L}+)\\s+((?:[0-9ivxlcIVXLC]\\s?){1,6})$")

    /** Decorative letter-spaced headings as pdftotext renders them: "C h apter 8", "C h apter 1 0" -> "Chapter 10". */
    private fun unspaced(t: String): String {
        val m = letterSpaced.matchEntire(t) ?: return t
        val word = m.groupValues[1].replace(Regex("\\s"), "")
        return if (bareKeyword.matches(word)) word + " " + m.groupValues[2].replace(Regex("\\s"), "") else t
    }

    /** A chapter title line following a bare "Chapter 8": short, not a sentence. */
    private fun isTitleLine(t: String) =
        t.length in 3..80 && '\n' !in t && t.first().isUpperCase() && !sentenceEnd.containsMatchIn(t) && t.any { it.isLetter() }
    private val chapterMention = Regex("\\b(chapter|part|appendix)\\s+([0-9]{1,3}|[ivxlc]{1,6})\\b", RegexOption.IGNORE_CASE)
    // also matched with all spaces removed: letter-spaced "C ONTENTS"
    private val contentsTitle = Regex("^(table ?of ?contents|contents|brief ?contents|contents ?at ?a ?glance|contents ?in ?detail)$", RegexOption.IGNORE_CASE)
    // "3.2 Foo .... 45", "Chapter 4 Bar 67", "Preface xiii": an entry with a page number
    private val tocEntry = Regex("^(?:(?:chapter|part|appendix)\\s+\\S+|\\d{1,2}(?:\\.\\d{1,2}){0,3}\\.?)?\\s*.{2,100}?(?:\\s*\\.{2,}\\s*|\\s+)([0-9]{1,4}|[ivxlc]{1,7})$", RegexOption.IGNORE_CASE)

    /**
     * Assign heuristic headings as sections when the document carries (almost) no structural headings.
     * Pages that look like a table of contents ([tocPages]) contribute no headings: each of their entries
     * looks like a heading, so the last one ("Chapter 15 …") would otherwise label all the front matter that
     * follows. The section is reset there instead, until the first real heading.
     */
    fun inferSections(paragraphs: List<Paragraph>, tocPages: Set<Int> = emptySet()): List<Paragraph> {
        val explicit = paragraphs.mapNotNullTo(HashSet()) { it.section }.size
        if (explicit >= 5) return paragraphs
        val toc = tocPages + tocPages(paragraphs)
        var current: String? = null
        var seenHeading = false
        return paragraphs.mapIndexed { i, p ->
            if (p.page != null && p.page in toc) {
                // before the first real heading this is the contents: front matter after it gets no label. Later a
                // contents-like page (a chapter's own mini-contents) only contributes no headings.
                if (!seenHeading) current = null
                return@mapIndexed if (current != null) p.copy(section = current) else p
            }
            headingOf(p.text)?.let { h ->
                seenHeading = true
                // "Chapter 8" on its own line, the title on the next one of the same page
                val next = paragraphs.getOrNull(i + 1)
                val title = next?.text?.trim()?.takeIf { bareChapter.matches(h) && next.page == p.page && isTitleLine(it) }
                current = (if (title != null) "$h $title" else h).take(200)
            }
            if (current != null) p.copy(section = current) else p
        }
    }

    /**
     * Pages near the front of a PDF that read like a table of contents: three or more dot leaders; a "Contents"
     * title with two or more entries; or, on a page without prose, four or more entries (heading-like lines, or
     * lines ending in a page number), four or more different chapters/parts named, or five of these and columns of
     * bare page numbers together. A page right after one continues it with two entries (or a dot leader or a page
     * number column) and no prose. A chapter page names one chapter and a heading or two.
     */
    fun tocPages(paragraphs: List<Paragraph>): Set<Int> = tocPageStats(paragraphs).filter { it.toc }.mapTo(HashSet()) { it.page }

    /** Per-page evidence behind [tocPages] (front pages only), for tuning. */
    internal data class TocPageStats(
        val page: Int, val entries: Int, val chapters: Int, val leaders: Int, val numberColumns: Int, val titled: Boolean,
        val prose: Boolean,
        val toc: Boolean,
    )

    internal fun tocPageStats(paragraphs: List<Paragraph>): List<TocPageStats> {
        val lastPage = paragraphs.maxOfOrNull { it.page ?: 0 } ?: 0
        // contents are at the front; further in, a table or a page of references must not reset a chapter's label
        val front = maxOf(40, lastPage * 15 / 100)
        val toc = HashSet<Int>()
        val out = ArrayList<TocPageStats>()
        paragraphs.filter { it.page != null && it.page <= front }.groupBy { it.page!! }.toSortedMap().forEach { (page, ps) ->
            val isEntry = ps.map { p ->
                val t = p.text.trim()
                '\n' !in t && t.length <= 120 && (headingOf(t) != null || isTocEntry(t))
            }
            val entries = isEntry.count { it }
            // chapters named outside prose and outside the entries already counted (a lone "Part I" counts once)
            val chapters = ps.filterIndexed { i, p -> !isEntry[i] && !isProse(p.text) }.flatMapTo(HashSet()) { p ->
                chapterMention.findAll(p.text).map { it.groupValues[1].lowercase() + " " + it.groupValues[2].lowercase() }
            }.size
            val titled = ps.any { contentsTitle.matches(it.text.trim()) || contentsTitle.matches(it.text.replace(Regex("\\s"), "")) }
            // dotted entries ("Summary . . . . 322") get joined into one long paragraph per block
            val leaders = ps.sumOf { TextCleaner.dotLeaderCount(it.text) }
            // the page numbers as a column of their own: "75 75 87 87 94 117"
            val numberColumns = ps.count { pageNumbers.matches(it.text.trim()) }
            val signals = entries + chapters + leaders + numberColumns
            // contents have no prose; tables, diagrams and part openers listing their chapters usually do
            val prose = ps.any { isProse(it.text) }
            // contents spill over onto pages with only a couple of entries
            val continues = (page - 1) in toc && (entries >= 2 || leaders + numberColumns >= 1) && !prose
            val isToc = leaders >= 3 || (titled && signals >= 2) || continues ||
                (!prose && (entries >= 4 || chapters >= 4 || signals >= 5))
            if (isToc) toc += page
            out += TocPageStats(page, entries, chapters, leaders, numberColumns, titled, prose, isToc)
        }
        return out
    }

    /**
     * Running text, as opposed to a joined block of contents entries: long, no dot leaders, mostly words starting in
     * lower case (contents titles are often capitalised: "Why Automate? Manual Testing Takes Too Long"), and at
     * least two sentence breaks.
     */
    private fun isProse(t: String): Boolean {
        if (t.length < 150 || TextCleaner.dotLeaderCount(t) > 0) return false
        val words = wordStart.findAll(t).map { it.value }.toList()
        // sentence-case contents ("Creating a database and a collection") are lower case too, but have no sentences
        return words.size >= 20 && words.count { it.first().isLowerCase() } * 2 >= words.size &&
            sentenceBreak.findAll(t).count() >= 2
    }

    private val sentenceBreak = Regex("\\p{Ll}[.]\\s+\\p{Lu}")

    private val wordStart = Regex("\\p{L}{2,}")

    private fun isTocEntry(t: String): Boolean {
        val m = tocEntry.matchEntire(t) ?: return false
        // a sentence ending in a number ("… was released in 2009") isn't an entry; entries rarely end a sentence
        return !t.dropLast(m.groupValues[1].length).trimEnd().endsWith('.') || Regex("\\.{2,}\\s*\\S+$").containsMatchIn(t)
    }

    /** Re-joins paragraphs split mid-sentence (page breaks, broken MOBI markup). Keeps the first one's location. */
    fun mergeBroken(paragraphs: List<Paragraph>, maxLen: Int): List<Paragraph> {
        val out = ArrayList<Paragraph>(paragraphs.size)
        for (p in paragraphs) {
            val prev = out.lastOrNull()
            if (prev != null && continues(prev.text, p.text) && prev.text.length + p.text.length < maxLen &&
                (prev.section == p.section || p.section == null)
            ) {
                out[out.size - 1] = prev.copy(text = prev.text.trimEnd() + " " + p.text.trimStart())
            } else {
                out += p
            }
        }
        return out
    }

    private fun continues(prev: String, next: String): Boolean {
        val a = prev.trimEnd()
        val b = next.trimStart()
        if (a.isEmpty() || b.isEmpty() || '\n' in a.takeLast(200) && a.lines().size > 3) return false
        if (sentenceEnd.containsMatchIn(a)) return false
        return b.first().isLowerCase()
    }

    /**
     * Drops running headers/footers in PDFs: short lines in the first/last lines of a page that recur (digits
     * normalised) on many pages, plus bare page numbers.
     */
    fun stripPageFurniture(pages: List<String>): List<String> {
        val edge = 5
        fun norm(l: String) = l.trim().lowercase().replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ")
        fun edgeLines(lines: List<String>): List<IndexedValue<String>> {
            val nonBlank = lines.withIndex().filter { it.value.isNotBlank() }
            return (nonBlank.take(edge) + nonBlank.takeLast(edge)).distinctBy { it.index }
        }
        val pageLines = pages.map { it.lines() }
        val counts = HashMap<String, Int>()
        pageLines.forEach { lines ->
            edgeLines(lines).map { norm(it.value) }.filter { it.length <= 100 }.toSet().forEach { counts.merge(it, 1, Int::plus) }
        }
        val threshold = maxOf(4, pages.size / 20)
        val pageNumber = Regex("^(page\\s+)?(\\d{1,4}|[ivxlcdm]{1,7})$", RegexOption.IGNORE_CASE)
        return pageLines.map { lines ->
            val drop = edgeLines(lines).filter { (_, l) ->
                val n = norm(l)
                pageNumber.matches(l.trim()) ||
                    (n.any { it.isLetterOrDigit() || it == '#' } && (counts[n] ?: 0) >= threshold)
            }.mapTo(HashSet()) { it.index }
            if (drop.isEmpty()) lines.joinToString("\n")
            else lines.filterIndexed { i, _ -> i !in drop }.joinToString("\n")
        }
    }

    /** Drops short single-line paragraphs that repeat many times (running headers in converted ebooks). */
    fun dropRepeatedLines(paragraphs: List<Paragraph>, minRepeats: Int = 8): List<Paragraph> {
        fun key(t: String) = t.trim().lowercase().replace(Regex("\\d+"), "#")
        val candidates = paragraphs.filter { isRepeatCandidate(it.text) }.groupingBy { key(it.text) }.eachCount()
        val repeated = candidates.filterValues { it >= minRepeats }.keys
        if (repeated.isEmpty()) return paragraphs
        return paragraphs.filterNot { isRepeatCandidate(it.text) && key(it.text) in repeated }
    }

    private fun isRepeatCandidate(t: String) =
        t.length <= 80 && '\n' !in t.trim() && t.any { it.isLetter() } && !sentenceEnd.containsMatchIn(t.trim())
}
