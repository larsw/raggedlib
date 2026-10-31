package dev.raggedlib.extract

import java.text.BreakIterator
import java.util.Locale

/**
 * Paragraph/sentence-aware chunker. Packs paragraphs into chunks of about [target] chars (never more than
 * [max]), splitting oversized paragraphs at sentence boundaries (or whitespace as a last resort), and carries
 * roughly [overlap] chars of trailing sentences into the next chunk. A chunk's page/section is that of its
 * first non-overlap paragraph. A new section starts a new chunk once the current one is reasonably full.
 */
class Chunker(
    private val target: Int = 1500,
    private val max: Int = 2000,
    private val overlap: Int = 200,
    private val minChars: Int = 80,
) {
    init {
        require(target in 1..max) { "target must be <= max" }
        require(overlap in 0 until target) { "overlap must be < target" }
    }

    private data class Unit(val text: String, val page: Int?, val section: String?)

    fun chunk(paragraphs: List<Paragraph>): List<Chunk> {
        val units = paragraphs.filterNot { TextCleaner.isJunkParagraph(it.text) }.flatMap { p -> splitToUnits(p.text).map { Unit(it, p.page, p.section) } }
        val raw = mutableListOf<Triple<String, Int?, String?>>()

        val current = mutableListOf<String>()
        var currentLen = 0
        var first: Unit? = null // first non-overlap unit of the current chunk

        fun emit(withOverlap: Boolean) {
            val f = first ?: return
            val text = current.joinToString("\n\n")
            raw += Triple(text, f.page, f.section)
            current.clear()
            currentLen = 0
            first = null
            if (withOverlap && overlap > 0) {
                val tail = tailForOverlap(text)
                if (tail.isNotEmpty()) {
                    current += tail
                    currentLen = tail.length
                }
            }
        }

        for (u in units) {
            val sectionChanged = first != null && u.section != null && u.section != first!!.section
            if (first != null) {
                when {
                    sectionChanged && currentLen >= target / 3 -> emit(withOverlap = false)
                    currentLen >= target || currentLen + 2 + u.text.length > max -> emit(withOverlap = true)
                }
            }
            if (first == null && current.isNotEmpty() && currentLen + 2 + u.text.length > max) {
                // overlap tail + this unit would not fit: drop the overlap
                current.clear()
                currentLen = 0
            }
            current += u.text
            currentLen += (if (currentLen > 0) 2 else 0) + u.text.length
            if (first == null) first = u
        }
        emit(withOverlap = false)

        return raw.filterNot { TextCleaner.isJunkChunk(it.first, minChars) }
            .mapIndexed { i, (text, page, section) -> Chunk(i, text, page, section) }
    }

    /** Splits a paragraph into pieces of at most [max] chars, preferring sentence boundaries. */
    internal fun splitToUnits(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= max) return listOf(t)
        val sentences = sentences(t)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in sentences.flatMap { hardSplit(it, target) }) {
            if (sb.isNotEmpty() && sb.length + 1 + s.length > target) {
                out += sb.toString()
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun tailForOverlap(text: String): String {
        val sents = sentences(text)
        val picked = ArrayDeque<String>()
        var len = 0
        for (s in sents.asReversed()) {
            if (len + s.length + 1 > overlap) break
            picked.addFirst(s)
            len += s.length + 1
        }
        if (picked.isNotEmpty()) return picked.joinToString(" ")
        // last sentence alone is longer than the overlap: take its last `overlap` chars from a word boundary
        val tail = text.takeLast(overlap)
        val cut = tail.indexOf(' ')
        return if (cut in 0 until tail.length - 1) tail.substring(cut + 1).trim() else ""
    }

    private fun sentences(text: String): List<String> {
        val it = BreakIterator.getSentenceInstance(Locale.ENGLISH)
        it.setText(text)
        val out = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val s = text.substring(start, end).trim()
            if (s.isNotEmpty()) out += s
            start = end
            end = it.next()
        }
        return out
    }

    private fun hardSplit(s: String, limit: Int): List<String> {
        if (s.length <= limit) return listOf(s)
        val out = mutableListOf<String>()
        var rest = s
        while (rest.length > limit) {
            var cut = rest.lastIndexOf(' ', limit)
            if (cut < limit / 2) cut = limit
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
