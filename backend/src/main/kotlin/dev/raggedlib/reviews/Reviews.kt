package dev.raggedlib.reviews

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.LibraryRole
import dev.raggedlib.config.ApiException
import dev.raggedlib.config.badRequest
import dev.raggedlib.config.notFound
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class Review(
    val id: Long,
    val bookId: Long,
    val bookTitle: String,
    val userId: Long,
    val author: String,
    /** 1-5, or null for a review without a rating. */
    val rating: Int?,
    val body: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Written by the caller. */
    val mine: Boolean,
)

/** GET /api/books/{id}/reviews. */
data class BookReviews(
    val bookId: Long,
    val count: Int,
    /** Mean of the ratings given (reviews without one don't count); null when there are none. */
    val averageRating: Double?,
    val ratings: Int,
    val reviews: List<Review>,
    /** The caller's own review, if any (also in [reviews]). */
    val mine: Review?,
    /** The caller may remove others' reviews of this book (a curator of its library). */
    val canModerate: Boolean,
)

data class ReviewRequest(val rating: Int? = null, val body: String? = null)

/**
 * Book reviews: one per user and book, with a 1-5 rating, a text, or both. Everyone who can see a book can read its
 * reviews; authors change or remove their own, and the library's curators can remove any.
 */
@Service
class ReviewService(private val jdbc: NamedParameterJdbcTemplate, private val access: Access) {
    private val select = """
        select r.id, r.book_id, b.title, r.user_id, coalesce(u.display_name, u.email, u.subject) as author,
               r.rating, r.body, r.created_at, r.updated_at
        from book_reviews r join users u on u.id = r.user_id join books b on b.id = r.book_id
    """.trimIndent()

    private fun mapper(me: Long?) = RowMapper { rs, _ ->
        Review(
            id = rs.getLong("id"),
            bookId = rs.getLong("book_id"),
            bookTitle = rs.getString("title"),
            userId = rs.getLong("user_id"),
            author = rs.getString("author"),
            rating = rs.getObject("rating")?.let { (it as Number).toInt() },
            body = rs.getString("body"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            mine = rs.getLong("user_id") == me,
        )
    }

    fun forBook(bookId: Long): BookReviews {
        val row = access.requireBook(bookId)
        val me = access.caller().userId
        val reviews = jdbc.query("$select where r.book_id = :bookId order by r.updated_at desc, r.id", mapOf("bookId" to bookId), mapper(me))
        val rated = reviews.mapNotNull { it.rating }
        return BookReviews(
            bookId = bookId,
            count = reviews.size,
            averageRating = if (rated.isEmpty()) null else Math.round(rated.average() * 100) / 100.0,
            ratings = rated.size,
            reviews = reviews,
            mine = reviews.firstOrNull { it.mine },
            canModerate = access.role(row.book.libraryId).atLeast(LibraryRole.CURATOR),
        )
    }

    /** The caller's reviews, of books it can still see, newest first. */
    fun mine(): List<Review> {
        val me = access.userId()
        val visible = access.libraries().ifEmpty { setOf(-1L) }
        return jdbc.query(
            "$select where r.user_id = :me and b.library_id in (:visible) order by r.updated_at desc, r.id",
            mapOf("me" to me, "visible" to visible), mapper(me),
        )
    }

    /** Writes or replaces the caller's review of [bookId]. */
    fun write(bookId: Long, req: ReviewRequest): Review {
        access.requireBook(bookId)
        val me = access.userId()
        if (req.rating != null && req.rating !in 1..5) badRequest("rating must be 1 to 5")
        val body = req.body?.trim().orEmpty()
        if (body.length > MAX_BODY) badRequest("a review is at most $MAX_BODY characters")
        if (req.rating == null && body.isEmpty()) badRequest("give a rating, a text, or both")
        val id = jdbc.queryForObject(
            """
            insert into book_reviews (book_id, user_id, rating, body) values (:bookId, :me, :rating, :body)
            on conflict (book_id, user_id) do update set rating = excluded.rating, body = excluded.body, updated_at = now()
            returning id
            """.trimIndent(),
            MapSqlParameterSource().addValue("bookId", bookId).addValue("me", me).addValue("rating", req.rating).addValue("body", body),
            Long::class.java,
        )!!
        return jdbc.query("$select where r.id = :id", mapOf("id" to id), mapper(me)).single()
    }

    fun deleteMine(bookId: Long) {
        access.requireBook(bookId)
        val n = jdbc.update("delete from book_reviews where book_id = :bookId and user_id = :me", mapOf("bookId" to bookId, "me" to access.userId()))
        if (n == 0) notFound("you haven't reviewed book $bookId")
    }

    /** Removes review [id]: the author's own, or any review of a book the caller curates. */
    fun delete(id: Long) {
        val review = jdbc.query("$select where r.id = :id", mapOf("id" to id), mapper(access.caller().userId)).firstOrNull()
            ?: notFound("review $id not found")
        val book = access.requireBook(review.bookId)
        if (!review.mine && !access.role(book.book.libraryId).atLeast(LibraryRole.CURATOR)) {
            throw ApiException(HttpStatus.FORBIDDEN, "only its author or a curator of the library can remove a review")
        }
        jdbc.update("delete from book_reviews where id = :id", mapOf("id" to id))
    }

    private companion object {
        const val MAX_BODY = 20_000
    }
}

@RestController
@RequestMapping("/api")
class ReviewController(private val reviews: ReviewService) {
    @GetMapping("/books/{bookId}/reviews")
    fun forBook(@PathVariable bookId: Long): BookReviews = reviews.forBook(bookId)

    @PutMapping("/books/{bookId}/reviews/mine")
    fun write(@PathVariable bookId: Long, @RequestBody req: ReviewRequest): Review = reviews.write(bookId, req)

    @DeleteMapping("/books/{bookId}/reviews/mine")
    fun deleteMine(@PathVariable bookId: Long): ResponseEntity<Void> {
        reviews.deleteMine(bookId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/reviews/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<Void> {
        reviews.delete(id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me/reviews")
    fun mine(): List<Review> = reviews.mine()
}
