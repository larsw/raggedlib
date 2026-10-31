package dev.raggedlib.calibre

object Formats {
    /** Preference order for text extraction. */
    val PREFERENCE = listOf("EPUB", "AZW3", "MOBI", "FB2", "PDF", "LIT", "CHM", "DJVU")

    fun choose(available: Collection<String>): String? {
        val upper = available.map { it.uppercase() }.toSet()
        return PREFERENCE.firstOrNull { it in upper }
    }

    fun contentType(format: String): String = when (format.uppercase()) {
        "PDF" -> "application/pdf"
        "EPUB" -> "application/epub+zip"
        "MOBI", "AZW", "PRC" -> "application/x-mobipocket-ebook"
        "AZW3" -> "application/vnd.amazon.ebook"
        "DJVU" -> "image/vnd.djvu"
        "CHM" -> "application/vnd.ms-htmlhelp"
        "FB2" -> "application/x-fictionbook+xml"
        "LIT" -> "application/x-ms-reader"
        "TXT" -> "text/plain; charset=utf-8"
        "HTML", "HTM" -> "text/html"
        "DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "RTF" -> "application/rtf"
        "CBZ", "ZIP" -> "application/zip"
        else -> "application/octet-stream"
    }
}
