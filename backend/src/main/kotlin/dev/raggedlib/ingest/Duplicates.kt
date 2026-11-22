package dev.raggedlib.ingest

import java.security.MessageDigest
import java.util.HexFormat

/**
 * Duplicate books: a Calibre library can hold the same book twice (two entries, re-imports, a mislabelled file),
 * and indexing both doubles every hit. A book is a duplicate of another when (nearly) all its passages are, word
 * for word, passages of that other book, which is at least as long — so of two identical copies the one with the
 * lower id is kept, and of a book and its slightly longer copy the longer one.
 *
 * A duplicate's passages are its copy's (search, Ask and the vector space use them), and a reader who sees only the
 * duplicate's library is shown them as the duplicate's. So across libraries only an exact copy counts, the same
 * passages and no others: a copy with more text would show readers passages of a library they can't see.
 */
object Duplicates {
    /** Another book, in [libraryId], holding [shared] of the new book's distinct passages; [chunks] passages in all. */
    data class Match(val bookId: Long, val shared: Int, val chunks: Int, val libraryId: Long)

    /** md5 hex of the passage text, equal to Postgres' `md5(content)`. */
    fun md5(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)))

    /**
     * The book that [bookId] (in [libraryId], with [chunks] passages, [distinct] different ones) duplicates, or null:
     * one holding at least [minShare] of its distinct passages (and at least [minShared], or all of a shorter book),
     * with at least as many passages (equal: the lower id is kept). Several: the one sharing most. One in another
     * library only if it holds all of them and no others ([distinctOf]: a book's number of different passages, asked
     * only for such a candidate).
     */
    fun keeper(
        bookId: Long, libraryId: Long, chunks: Int, distinct: Int, matches: List<Match>, minShare: Double, minShared: Int,
        distinctOf: (Long) -> Int,
    ): Match? =
        matches.asSequence()
            .filter { it.bookId != bookId && distinct > 0 }
            .filter { it.shared >= minShared.coerceAtMost(distinct) && it.shared >= minShare * distinct }
            .filter { it.chunks > chunks || (it.chunks == chunks && it.bookId < bookId) }
            .filter { it.libraryId == libraryId || (it.shared == distinct && distinctOf(it.bookId) == distinct) }
            .maxWithOrNull(compareBy<Match>({ it.shared }, { it.chunks }, { -it.bookId }))
}
