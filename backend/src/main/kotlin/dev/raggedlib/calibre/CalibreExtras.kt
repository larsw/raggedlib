package dev.raggedlib.calibre

import org.springframework.web.util.HtmlUtils
import java.time.Instant

/** Calibre metadata that only the book detail view needs (read from metadata.db on request). */
data class CalibreExtras(
    /** Calibre's "comments", as plain text with paragraphs separated by blank lines. */
    val description: String?,
    /** True when editing the plain-text description would remove markup beyond paragraphs and line breaks. */
    val descriptionHasFormatting: Boolean,
    val titleSort: String?,
    val authorSort: String?,
    val publisher: String?,
    /** e.g. isbn, amazon, google, doi -> value. */
    val identifiers: Map<String, String>,
    /** 0.5-5 stars; null when unrated. */
    val rating: Double?,
    /** When the book was added to the Calibre library. */
    val addedAt: Instant?,
    /** Format -> file size in bytes. */
    val fileSizes: Map<String, Long>,
)

/**
 * Calibre stores descriptions as HTML (from publishers and metadata sources). The detail view shows them as text:
 * rendering third-party HTML would need sanitising, and the markup carries little beyond paragraphs and lists.
 */
object CommentText {
    private val block = Regex("(?i)<\\s*(br|/p|/div|/h[1-6]|/li|/ul|/ol|/blockquote|/tr)\\b[^>]*>")
    private val listItem = Regex("(?i)<\\s*li\\b[^>]*>")
    private val tag = Regex("<[^>]*>")
    private val scriptOrStyle = Regex("(?is)<\\s*(script|style)\\b.*?<\\s*/\\s*\\1\\s*>")
    private val markup = Regex("(?i)<\\s*/?\\s*([a-z0-9]+)\\b([^>]*)>")
    private val plainTextTags = setOf("p", "br")

    fun toPlainText(html: String): String {
        val withBreaks = scriptOrStyle.replace(html, "")
            .let { block.replace(it, "\n") }
            .let { listItem.replace(it, "• ") } // the closing tag before it already broke the line
        val text = HtmlUtils.htmlUnescape(tag.replace(withBreaks, ""))
        return text.lines()
            .map { it.replace(' ', ' ').replace(Regex("\\s+"), " ").trim() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun hasFormatting(html: String): Boolean = markup.findAll(html).any { match ->
        match.groupValues[1].lowercase() !in plainTextTags || match.groupValues[2].trim().removeSuffix("/").isNotEmpty()
    }

    fun toSimpleHtml(text: String): String {
        if (text.isEmpty()) return ""
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split(Regex("\n[ \\t]*\n+")).joinToString("</p><p>", "<p>", "</p>") {
            HtmlUtils.htmlEscape(it).replace("\n", "<br>")
        }
    }
}
