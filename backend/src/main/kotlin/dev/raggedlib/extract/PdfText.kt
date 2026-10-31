package dev.raggedlib.extract

/** Turns `pdftotext` output (pages separated by form feeds) into paragraphs tagged with 1-based page numbers. */
object PdfText {

    fun splitPages(raw: String): List<String> {
        val pages = raw.split('\u000C')
        // pdftotext terminates every page (including the last) with \f -> drop the trailing empty remainder
        return if (pages.size > 1 && pages.last().isBlank()) pages.dropLast(1) else pages
    }

    fun letterCount(raw: String): Int = raw.count { it.isLetter() }

    fun paragraphs(raw: String): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        val pages = Structure.stripPageFurniture(splitPages(raw).map { TextCleaner.normalize(it) })
        pages.forEachIndexed { i, normalized ->
            val page = i + 1
            normalized.split(Regex("\\n\\s*\\n")).forEach { block ->
                val text = TextCleaner.joinWrappedLines(block)
                if (!TextCleaner.isJunkParagraph(text)) out += Paragraph(text, page, null)
            }
        }
        return out
    }
}
