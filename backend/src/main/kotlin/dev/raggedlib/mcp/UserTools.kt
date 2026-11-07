package dev.raggedlib.mcp

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.ApiScope
import dev.raggedlib.favorites.FavoriteList
import dev.raggedlib.favorites.FavoriteListRequest
import dev.raggedlib.favorites.FavoriteListService
import dev.raggedlib.favorites.FavoriteLists
import dev.raggedlib.reviews.BookReviews
import dev.raggedlib.reviews.Review
import dev.raggedlib.reviews.ReviewRequest
import dev.raggedlib.reviews.ReviewService
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * MCP tools over the key owner's own data: favorite lists and book reviews. They need a personal API key with the
 * matching scopes; a system-wide key doesn't see them.
 */
@Component
class UserTools(
    private val lists: FavoriteListService,
    private val reviews: ReviewService,
    private val access: Access,
) {
    /** A book on a favorite list, as an agent needs it. */
    data class ListedBook(val bookId: Long, val title: String, val authors: List<String>, val libraryId: Long, val note: String?)

    data class ListContents(val list: FavoriteList, val books: List<ListedBook>)

    @McpTool(
        name = "list_favorite_lists",
        title = "List favorite lists",
        description = """The user's favorite lists of books (with their ids and book counts), and the lists other users
share. Use get_favorite_list to see a list's books.""",
        annotations = McpAnnotations(title = "List favorite lists", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun listFavoriteLists(): FavoriteLists {
        access.requireScope(ApiScope.FAVORITES_READ)
        return lists.lists()
    }

    @McpTool(
        name = "get_favorite_list",
        title = "Get a favorite list",
        description = "The books on one favorite list (the user's own, or one another user shares), newest first, with their book ids for search_library's bookIds.",
        annotations = McpAnnotations(title = "Get a favorite list", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun getFavoriteList(
        @McpToolParam(description = "The list's id (from list_favorite_lists).") listId: Long,
    ): ListContents {
        access.requireScope(ApiScope.FAVORITES_READ)
        val d = lists.get(listId)
        return ListContents(d.list, d.books.map { ListedBook(it.book.id, it.book.title, it.book.authors, it.book.libraryId, it.note) })
    }

    @McpTool(
        name = "create_favorite_list",
        title = "Create a favorite list",
        description = "Create a new favorite list for the user: private unless shared is true (then every signed-in user can read it).",
        annotations = McpAnnotations(title = "Create a favorite list", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
    )
    fun createFavoriteList(
        @McpToolParam(description = "Name of the list.") name: String,
        @McpToolParam(description = "What the list is for.", required = false) description: String?,
        @McpToolParam(description = "Share the list with every signed-in user (default false: private).", required = false) shared: Boolean?,
    ): FavoriteList {
        access.requireScope(ApiScope.FAVORITES_WRITE)
        return lists.create(FavoriteListRequest(name, description, shared ?: false))
    }

    @McpTool(
        name = "add_to_favorite_list",
        title = "Add a book to a favorite list",
        description = "Put a book (id from list_books or a search result) on one of the user's own favorite lists, optionally with a note; adding it again updates the note.",
        annotations = McpAnnotations(title = "Add to favorite list", readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun addToFavoriteList(
        @McpToolParam(description = "The list's id (one of the user's own).") listId: Long,
        @McpToolParam(description = "The book's id.") bookId: Long,
        @McpToolParam(description = "A note on why the book is on the list.", required = false) note: String?,
    ): FavoriteList {
        access.requireScope(ApiScope.FAVORITES_WRITE)
        return lists.addBook(listId, bookId, note)
    }

    @McpTool(
        name = "remove_from_favorite_list",
        title = "Remove a book from a favorite list",
        description = "Take a book off one of the user's own favorite lists.",
        annotations = McpAnnotations(title = "Remove from favorite list", readOnlyHint = false, destructiveHint = true, idempotentHint = true, openWorldHint = false),
    )
    fun removeFromFavoriteList(
        @McpToolParam(description = "The list's id (one of the user's own).") listId: Long,
        @McpToolParam(description = "The book's id.") bookId: Long,
    ): FavoriteList {
        access.requireScope(ApiScope.FAVORITES_WRITE)
        return lists.removeBook(listId, bookId)
    }

    @McpTool(
        name = "get_book_reviews",
        title = "Get a book's reviews",
        description = "The reviews users wrote of a book (rating 1-5 and/or text), with the average rating.",
        annotations = McpAnnotations(title = "Get book reviews", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun getBookReviews(@McpToolParam(description = "The book's id.") bookId: Long): BookReviews {
        access.requireScope(ApiScope.REVIEWS_READ)
        return reviews.forBook(bookId)
    }

    @McpTool(
        name = "list_my_reviews",
        title = "List the user's reviews",
        description = "The reviews the user has written, newest first.",
        annotations = McpAnnotations(title = "List my reviews", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun listMyReviews(): List<Review> {
        access.requireScope(ApiScope.REVIEWS_READ)
        return reviews.mine()
    }

    @McpTool(
        name = "write_book_review",
        title = "Review a book",
        description = """Write the user's review of a book: a rating from 1 to 5, a text, or both. Replaces the user's
earlier review of the same book, if any. Only write a review the user asked for, in their words.""",
        annotations = McpAnnotations(title = "Review a book", readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun writeBookReview(
        @McpToolParam(description = "The book's id.") bookId: Long,
        @McpToolParam(description = "Rating, 1 (poor) to 5 (excellent).", required = false) rating: Int?,
        @McpToolParam(description = "The review text.", required = false) text: String?,
    ): Review {
        access.requireScope(ApiScope.REVIEWS_WRITE)
        return reviews.write(bookId, ReviewRequest(rating, text))
    }
}
