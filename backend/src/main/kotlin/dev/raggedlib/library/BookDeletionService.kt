package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.ApiScope
import dev.raggedlib.auth.LibraryRole
import dev.raggedlib.calibre.CalibreLibraryWriteLock
import dev.raggedlib.calibre.CalibreMetadataWriter
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

@Service
class BookDeletionService(
    private val access: Access,
    private val writer: CalibreMetadataWriter,
    private val writeLock: CalibreLibraryWriteLock,
    private val books: BookRepository,
    private val activity: BookActivityRepository,
    private val tx: TransactionTemplate,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun delete(bookId: Long) {
        access.requireScope(ApiScope.LIBRARY_EDIT)
        val authorized = access.requireBook(bookId, LibraryRole.CURATOR)
        val library = access.requireEditableLibrary(authorized.book.libraryId)
        val actor = access.activityActor()
        writeLock.withLibrary(library.root) {
            access.requireScope(ApiScope.LIBRARY_EDIT)
            val current = access.requireEditableBook(bookId)
            writer.removeBook(library, current.book.calibreId)
        }
        markDeleted(listOf(bookId), actor)
    }

    fun markDeleted(ids: Collection<Long>, actor: String): List<BookRow> {
        if (ids.isEmpty()) return emptyList()
        val deleted = tx.execute {
            val rows = books.softDelete(ids)
            if (rows.isNotEmpty()) {
                books.requeueDuplicatesOf(rows.map { it.book.id })
                rows.forEach { row ->
                    activity.recordEdit(
                        bookId = row.book.id,
                        libraryId = row.book.libraryId,
                        calibreId = row.book.calibreId,
                        title = row.book.title,
                        actor = actor,
                        changes = mapOf("deleted" to mapOf("old" to false, "new" to true)),
                        action = "DELETE_BOOK",
                    )
                }
            }
            rows
        } ?: emptyList()
        if (deleted.isNotEmpty()) visibilityChanged(deleted.size)
        return deleted
    }

    fun purgeExpiredIn(libraryId: Long): Int =
        books.purgeDeletedBefore(Instant.now().minus(RETENTION), libraryId)

    fun visibilityChanged(count: Int) {
        if (count > 0) events.publishEvent(BooksVisibilityChanged(count))
    }

    @Scheduled(cron = "0 0 2 * * *")
    fun purgeExpired() {
        val purged = books.purgeDeletedBefore(Instant.now().minus(RETENTION))
        if (purged > 0) log.info("Purged {} books deleted more than 14 days ago", purged)
    }

    private companion object {
        val RETENTION: Duration = Duration.ofDays(14)
    }
}

data class BooksVisibilityChanged(val count: Int)
