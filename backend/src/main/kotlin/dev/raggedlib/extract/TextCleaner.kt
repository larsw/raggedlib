package dev.raggedlib.extract

import java.text.Normalizer

object TextCleaner {
    private val invisible = Regex("[\\u00AD\\u200B-\\u200D\\u2060\\uFEFF\\u0000-\\u0008\\u000B\\u000E-\\u001F\\u007F]")
    private val hSpace = Regex("[ \\t\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]+")
    private val lineBreakHyphen = Regex("(\\p{L})[-\\u2010\\u2011]\\s*\\n\\s*(\\p{Ll})")
    // also "·" and U+FFFD: pdftotext's rendering of leader glyphs it has no character for
    private val dotLeader = Regex("(?:\\.\\s?){4,}|(?:…\\s?){2,}|(?:_\\s?){4,}|(?:·\\s?){4,}|\uFFFD{4,}")

    /** Unicode normalisation (ligatures etc.), invisible chars, horizontal whitespace; keeps '\n' and '\f'. */
    fun normalize(text: String): String {
        var t = text.replace("\r\n", "\n").replace('\r', '\n')
        t = Normalizer.normalize(t, Normalizer.Form.NFKC)
        t = invisible.replace(t, "")
        t = hSpace.replace(t, " ")
        return t
    }

    /** Joins hard-wrapped lines of one paragraph, removing line-break hyphenation ("exam-\nple" -> "example"). */
    fun joinWrappedLines(paragraph: String): String {
        val dehyphenated = lineBreakHyphen.replace(paragraph) { m -> m.groupValues[1] + m.groupValues[2] }
        return dehyphenated.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Collapses runs of spaces and trims each line; drops leading/trailing blank lines. */
    fun tidy(text: String): String =
        text.lines().joinToString("\n") { hSpace.replace(it, " ").trimEnd() }.trim()

    fun letterRatio(text: String): Double {
        var letters = 0
        var nonSpace = 0
        for (ch in text) {
            if (ch.isWhitespace()) continue
            nonSpace++
            if (ch.isLetter()) letters++
        }
        return if (nonSpace == 0) 0.0 else letters.toDouble() / nonSpace
    }

    fun digitRatio(text: String): Double {
        var digits = 0
        var nonSpace = 0
        for (ch in text) {
            if (ch.isWhitespace()) continue
            nonSpace++
            if (ch.isDigit()) digits++
        }
        return if (nonSpace == 0) 0.0 else digits.toDouble() / nonSpace
    }

    fun dotLeaderCount(text: String): Int = dotLeader.findAll(text).count()

    /** Paragraph-level junk: TOC lines with dot leaders, page furniture, number soup. */
    fun isJunkParagraph(p: String): Boolean {
        val t = p.trim()
        if (t.isEmpty()) return true
        if (dotLeaderCount(t) >= 2) return true
        if (t.length <= 4 && t.all { it.isDigit() || it.isWhitespace() }) return true // bare page numbers
        if (t.length > 20 && letterRatio(t) < 0.4) return true
        return false
    }

    /** Chunk-level junk: TOCs, indexes, tables of numbers, binary garbage. */
    fun isJunkChunk(c: String, minChars: Int): Boolean {
        val t = c.trim()
        if (t.length < minChars) return true
        if (letterRatio(t) < 0.5) return true
        if (digitRatio(t) > 0.25) return true
        val lines = t.lines().size.coerceAtLeast(1)
        if (dotLeaderCount(t) >= 5 && dotLeaderCount(t) * 4 >= lines) return true
        // back-of-book index: many short "term, 12, 45" fragments
        val pageRefs = Regex(",\\s*\\d{1,4}(?:[-–]\\d{1,4})?(?=[,\\s]|$)").findAll(t).count()
        if (pageRefs >= 15 && pageRefs * 60 > t.length) return true
        // index without page numbers (EPUB links): comma-separated term soup with hardly any sentences
        val commas = t.count { it == ',' }
        val sentences = Regex("[.!?](\\s|$)").findAll(t).count()
        if (commas >= 25 && commas * 100 > t.length * 2 && sentences * 8 < commas) return true
        return false
    }
}
