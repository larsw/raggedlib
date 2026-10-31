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
        val t = text.trim()
        if (t.length < 3 || t.length > 110 || '\n' in t) return null
        if (t.endsWith('.') || t.endsWith(',') || t.endsWith(';')) return null
        if (!chapterHeading.matches(t) && !numberedHeading.matches(t)) return null
        if (Regex("\\s\\d{1,4}$").containsMatchIn(t) && !chapterHeading.matches(t)) return null // TOC line "3.2 Foo 45"
        return t.replace(Regex("\\s+\\d{1,4}$"), "").trim() // "Chapter 10: Tools 73" -> "Chapter 10: Tools"
    }

    /**
     * Assign heuristic headings as sections when the document carries (almost) no structural headings.
     */
    fun inferSections(paragraphs: List<Paragraph>): List<Paragraph> {
        val explicit = paragraphs.mapNotNullTo(HashSet()) { it.section }.size
        if (explicit >= 5) return paragraphs
        var current: String? = null
        return paragraphs.map { p ->
            headingOf(p.text)?.let { current = it.take(200) }
            if (current != null) p.copy(section = current) else p
        }
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
