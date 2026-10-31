package dev.raggedlib.extract

/** A logical block of text with its location. */
data class Paragraph(val text: String, val page: Int?, val section: String?)

/** A chunk ready for embedding. `text` is the clean chunk text (no context header). */
data class Chunk(val index: Int, val text: String, val page: Int?, val section: String?)
