package dev.raggedlib.extract

/**
 * Turns `ebook-convert --txt-output-formatting=markdown` output into paragraphs, tracking the nearest heading
 * as `section`. Light markdown cleanup: images dropped, links reduced to their text, escapes removed.
 */
object MarkdownText {
    private val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val image = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]*)]\\((?:[^()]|\\([^)]*\\))*\\)")
    private val refLink = Regex("\\[([^\\]]+)]\\[[^\\]]*]")
    private val escape = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|<>~])")
    private val strong = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
    private val skipSections = Regex("^(table of contents|contents|index|brief contents|contents at a glance|contents in detail|symbols|numbers|[a-z])$", RegexOption.IGNORE_CASE)
    private val emphasis = Regex("(?<![\\w*\\\\])([*_])(?=[^\\s*_])([^*\\n]+?)(?<=[^\\s*_])\\1(?![\\w*])")
    private val starRun = Regex("\\*{2,}")
    private val htmlTag = Regex("</?(?:span|div|a|sup|sub|br|p|font)[^>]*>", RegexOption.IGNORE_CASE)

    fun cleanInline(s: String): String {
        var t = image.replace(s, "")
        t = link.replace(t) { it.groupValues[1] }
        t = refLink.replace(t) { it.groupValues[1] }
        t = strong.replace(t) { it.groupValues[2] }
        t = starRun.replace(t, "")
        t = emphasis.replace(t) { it.groupValues[2] }
        t = htmlTag.replace(t, "")
        t = escape.replace(t) { it.groupValues[1] }
        return t
    }

    fun headingText(line: String): String? =
        heading.matchEntire(line)?.groupValues?.get(2)?.let { cleanInline(it).trim() }?.takeIf { it.isNotEmpty() }

    fun paragraphs(raw: String): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        var section: String? = null
        val buf = StringBuilder()
        var inFence = false

        fun flush() {
            if (buf.isEmpty()) return
            val text = TextCleaner.tidy(buf.toString())
            buf.setLength(0)
            if (section != null && skipSections.matches(section!!)) return
            if (!TextCleaner.isJunkParagraph(text)) out += Paragraph(text, null, section)
        }

        for (rawLine in TextCleaner.normalize(raw).lines()) {
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence) {
                buf.append(line).append('\n')
                continue
            }
            val h = headingText(line)
            if (h != null) {
                flush()
                section = h.take(200)
                if (!skipSections.matches(h)) out += Paragraph(h, null, section)
                continue
            }
            if (line.isBlank()) {
                flush()
                continue
            }
            if (Regex("^\\s*([-*_]\\s*){3,}$").matches(line)) { // horizontal rule
                flush()
                continue
            }
            buf.append(cleanInline(line)).append('\n')
        }
        flush()
        return out
    }
}
