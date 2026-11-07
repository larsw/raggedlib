package dev.raggedlib.favorites

import dev.raggedlib.auth.Access
import dev.raggedlib.config.ApiException
import dev.raggedlib.config.badRequest
import dev.raggedlib.config.notFound
import dev.raggedlib.library.Book
import dev.raggedlib.library.BookRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** A favorite list, without its books. */
data class FavoriteList(
    val id: Long,
    val name: String,
    val description: String?,
    /** Shared lists are visible (read-only) to every signed-in user; others only to their owner. */
    val shared: Boolean,
    val ownerId: Long,
    val ownerName: String,
    /** The caller owns the list (and may change it). */
    val mine: Boolean,
    /** Books on the list that the caller can see (books in libraries it has no access to are left out). */
    val bookCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** When asked about a book (`?bookId=`): whether it is on this list. */
    val containsBook: Boolean? = null,
)

data class FavoriteListEntry(val book: Book, val note: String?, val addedAt: Instant)

data class FavoriteListDetails(val list: FavoriteList, val books: List<FavoriteListEntry>)

/** GET /api/lists: the caller's own lists, and the ones others share. */
data class FavoriteLists(val mine: List<FavoriteList>, val shared: List<FavoriteList>)

data class FavoriteListRequest(val name: String? = null, val description: String? = null, val shared: Boolean? = null)

data class FavoriteEntryRequest(val note: String? = null)

/**
 * Favorite lists of books: each user keeps their own, private or shared with every signed-in user. A list may hold
 * books of any library, but each reader only sees the books of libraries they can view.
 */
@Service
class FavoriteListService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val books: BookRepository,
    private val access: Access,
) {
    private val select = """
        select l.id, l.name, l.description, l.shared, l.owner_id, l.created_at, l.updated_at,
               coalesce(u.display_name, u.email, u.subject) as owner_name,
               (select count(*) from favorite_list_books fb join books b on b.id = fb.book_id
                 where fb.list_id = l.id and b.library_id in (:visible)) as book_count,
               exists (select 1 from favorite_list_books fb where fb.list_id = l.id and fb.book_id = :bookId) as contains_book
        from favorite_lists l join users u on u.id = l.owner_id
    """.trimIndent()

    private fun mapper(me: Long, withContains: Boolean) = RowMapper { rs, _ ->
        FavoriteList(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            shared = rs.getBoolean("shared"),
            ownerId = rs.getLong("owner_id"),
            ownerName = rs.getString("owner_name"),
            mine = rs.getLong("owner_id") == me,
            bookCount = rs.getInt("book_count"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            containsBook = if (withContains) rs.getBoolean("contains_book") else null,
        )
    }

    private fun params(me: Long, bookId: Long? = null) = MapSqlParameterSource()
        .addValue("me", me)
        // `in ()` is invalid SQL: an id no library has stands for "none"
        .addValue("visible", access.libraries().ifEmpty { setOf(-1L) })
        .addValue("bookId", bookId ?: -1L)

    fun lists(bookId: Long? = null): FavoriteLists {
        val me = access.userId()
        val m = mapper(me, bookId != null)
        val p = params(me, bookId)
        val mine = jdbc.query("$select where l.owner_id = :me order by lower(l.name), l.id", p, m)
        val shared = jdbc.query("$select where l.shared and l.owner_id <> :me order by l.updated_at desc, l.id", p, m)
        return FavoriteLists(mine, shared)
    }

    /** A list the caller may read (its own, or a shared one); 404 otherwise. */
    fun get(id: Long): FavoriteListDetails {
        val list = readable(id)
        val entries = jdbc.query(
            """
            select fb.book_id, fb.note, fb.added_at from favorite_list_books fb join books b on b.id = fb.book_id
            where fb.list_id = :id and b.library_id in (:visible) order by fb.added_at desc, fb.book_id
            """.trimIndent(),
            params(access.userId()).addValue("id", id),
        ) { rs, _ -> Triple(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()) }
        val byId = books.findByIds(entries.map { it.first }).associateBy { it.id }
        return FavoriteListDetails(list, entries.mapNotNull { (bookId, note, at) -> byId[bookId]?.let { FavoriteListEntry(it, note, at) } })
    }

    @Transactional
    fun create(req: FavoriteListRequest): FavoriteList {
        val me = access.userId()
        val name = validName(req.name) ?: badRequest("name must not be empty")
        val id = jdbc.queryForObject(
            "insert into favorite_lists (owner_id, name, description, shared) values (:me, :name, :description, :shared) returning id",
            MapSqlParameterSource()
                .addValue("me", me)
                .addValue("name", name)
                .addValue("description", validDescription(req.description))
                .addValue("shared", req.shared ?: false),
            Long::class.java,
        )!!
        return readable(id)
    }

    @Transactional
    fun update(id: Long, req: FavoriteListRequest): FavoriteList {
        owned(id)
        val sets = mutableListOf("updated_at = now()")
        val p = MapSqlParameterSource("id", id)
        req.name?.let { sets += "name = :name"; p.addValue("name", validName(it) ?: badRequest("name must not be empty")) }
        req.description?.let { sets += "description = :description"; p.addValue("description", validDescription(it)) }
        req.shared?.let { sets += "shared = :shared"; p.addValue("shared", it) }
        jdbc.update("update favorite_lists set ${sets.joinToString()} where id = :id", p)
        return readable(id)
    }

    fun delete(id: Long) {
        owned(id)
        jdbc.update("delete from favorite_lists where id = :id", mapOf("id" to id))
    }

    /**
     * Adds [bookId] (which the owner must be able to see), or updates its note: a null [note] keeps the one the book
     * has, a blank one clears it.
     */
    @Transactional
    fun addBook(id: Long, bookId: Long, note: String?): FavoriteList {
        owned(id)
        access.requireBook(bookId)
        jdbc.update(
            """
            insert into favorite_list_books (list_id, book_id, note) values (:id, :bookId, :note)
            on conflict (list_id, book_id) do update
                set note = case when :keep then favorite_list_books.note else excluded.note end
            """.trimIndent(),
            mapOf("id" to id, "bookId" to bookId, "note" to note?.trim()?.take(MAX_NOTE)?.ifEmpty { null }, "keep" to (note == null)),
        )
        touch(id)
        return readable(id)
    }

    @Transactional
    fun removeBook(id: Long, bookId: Long): FavoriteList {
        owned(id)
        val n = jdbc.update("delete from favorite_list_books where list_id = :id and book_id = :bookId", mapOf("id" to id, "bookId" to bookId))
        if (n == 0) notFound("book $bookId is not on list $id")
        touch(id)
        return readable(id)
    }

    private fun touch(id: Long) = jdbc.update("update favorite_lists set updated_at = now() where id = :id", mapOf("id" to id))

    private fun readable(id: Long): FavoriteList {
        val me = access.userId()
        return jdbc.query("$select where l.id = :id and (l.owner_id = :me or l.shared)", params(me).addValue("id", id), mapper(me, false))
            .firstOrNull() ?: notFound("list $id not found")
    }

    private fun owned(id: Long) {
        val list = readable(id)
        if (!list.mine) throw ApiException(HttpStatus.FORBIDDEN, "only its owner can change list $id")
    }

    private fun validName(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (it.length > MAX_NAME) badRequest("name must be at most $MAX_NAME characters")
    }

    private fun validDescription(s: String?): String? = s?.trim()?.ifEmpty { null }?.also {
        if (it.length > MAX_DESCRIPTION) badRequest("description must be at most $MAX_DESCRIPTION characters")
    }

    private companion object {
        const val MAX_NAME = 200
        const val MAX_DESCRIPTION = 2000
        const val MAX_NOTE = 2000
    }
}

@RestController
@RequestMapping("/api/lists")
class FavoriteListController(private val lists: FavoriteListService) {
    /** With `bookId`, each list says whether it holds that book (`containsBook`). */
    @GetMapping
    fun lists(@RequestParam(required = false) bookId: Long?): FavoriteLists = lists.lists(bookId)

    @PostMapping
    fun create(@RequestBody req: FavoriteListRequest): ResponseEntity<FavoriteList> =
        ResponseEntity.status(HttpStatus.CREATED).body(lists.create(req))

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): FavoriteListDetails = lists.get(id)

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody req: FavoriteListRequest): FavoriteList = lists.update(id, req)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<Void> {
        lists.delete(id)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/{id}/books/{bookId}")
    fun addBook(@PathVariable id: Long, @PathVariable bookId: Long, @RequestBody(required = false) req: FavoriteEntryRequest?): FavoriteList =
        lists.addBook(id, bookId, req?.note)

    @DeleteMapping("/{id}/books/{bookId}")
    fun removeBook(@PathVariable id: Long, @PathVariable bookId: Long): FavoriteList = lists.removeBook(id, bookId)
}
