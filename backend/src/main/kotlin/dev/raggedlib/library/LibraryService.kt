package dev.raggedlib.library

import dev.raggedlib.calibre.CalibreMetadataSource
import dev.raggedlib.calibre.CalibreBookIdentity
import dev.raggedlib.calibre.Formats
import dev.raggedlib.ingest.IngestService
import dev.raggedlib.config.notFound
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.Locale

@Service
class LibraryService(
    private val calibre: CalibreMetadataSource,
    private val books: BookRepository,
    private val registry: LibraryRegistry,
    private val ingest: IngestService,
    private val tx: TransactionTemplate,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val syncLock = Any()

    /**
     * Syncs the configured libraries (only [libraryIds], if given); one library's failure (calibredb down, path
     * unmounted) doesn't block the rest.
     */
    fun sync(libraryIds: Set<Long>? = null): SyncResult = synchronized(syncLock) {
        var added = 0
        var updated = 0
        var removed = 0
        var total = 0
        val sources = LinkedHashSet<String>()
        for (lib in registry.all) {
            if (libraryIds != null && lib.id !in libraryIds) continue
            try {
                val (a, u, r, t, source) = syncOne(lib)
                added += a; updated += u; removed += r; total += t
                sources += source
            } catch (e: Exception) {
                log.warn("Sync failed for library '{}' ({}): {}", lib.name, lib.root, e.message)
            }
        }
        SyncResult(added, updated, removed, total, sources.joinToString("+").ifEmpty { "none" })
    }

    /** Syncs one library and returns the Calibre records identified by [calibreIds], propagating sync failures. */
    fun syncAndFindBooks(libraryId: Long, calibreIds: Set<Long>): Map<Long, Book> {
        if (calibreIds.isEmpty()) return emptyMap()
        return synchronized(syncLock) {
            val lib = registry.byId(libraryId)
            syncOne(lib)
            val byCalibreId = books.findAllByLibrary(libraryId).associate { it.book.calibreId to it.book }
            calibreIds.associateWith { calibreId ->
                byCalibreId[calibreId] ?: error("Calibre added book $calibreId, but sync did not find it in library '${lib.name}'")
            }
        }
    }

    fun syncAndFindMatchingBooks(libraryId: Long, identity: CalibreBookIdentity): List<Book> = synchronized(syncLock) {
        val lib = registry.byId(libraryId)
        syncOne(lib)
        val title = normalizeIdentity(identity.title)
        val authors = identity.authors.map(::normalizeIdentity).sorted()
        books.findAllByLibrary(libraryId).map { it.book }.filter { book ->
            normalizeIdentity(book.title) == title && book.authors.map(::normalizeIdentity).sorted() == authors
        }
    }

    private fun normalizeIdentity(value: String) =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun syncOne(lib: LibraryInfo): SyncResult {
        val snapshot = calibre.load(lib.root)
        val existing = books.findAllByLibrary(lib.id).associateBy { it.book.calibreId }
        var added = 0
        var updated = 0
        tx.executeWithoutResult {
            for (r in snapshot.records) {
                val old = existing[r.id]
                val preferred = Formats.choose(r.formats.keys)
                if (old == null) {
                    books.insert(lib.id, r, preferred)
                    added++
                    continue
                }
                val changed = old.lastModified != r.lastModified || old.formatPaths.keys != r.formats.keys
                // also refreshes what an older index didn't store (publisher, before V7) without re-indexing
                val metadataOnly = old.formatPaths != r.formats || old.coverPath != r.coverPath ||
                    old.book.publisher != r.publisher
                if (changed) {
                    // passages stay searchable until re-indexing replaces them (reusing unchanged embeddings)
                    books.updateMetadata(old.book.id, r, preferred, reset = true)
                    updated++
                } else if (metadataOnly) {
                    books.updateMetadata(old.book.id, r, old.book.sourceFormat ?: preferred, reset = false)
                }
            }
        }
        val seen = snapshot.records.mapTo(HashSet()) { it.id }
        val gone = existing.values.filter { it.book.calibreId !in seen }.map { it.book.id }
        val removed = books.delete(gone) // chunks go via ON DELETE CASCADE, duplicate_of via SET NULL
        // duplicates of removed books need passages of their own now (also catches one marked while deleting)
        val orphaned = books.requeueOrphanedDuplicates()
        if (orphaned.isNotEmpty()) log.info("Duplicates {} of removed books are pending again", orphaned)
        log.info("Library '{}' sync via {}: +{} ~{} -{} (total {})", lib.name, snapshot.source, added, updated, removed, snapshot.records.size)
        if (removed > 0) events.publishEvent(BooksRemoved(removed))
        return SyncResult(added, updated, removed, snapshot.records.size, snapshot.source)
    }

    fun reindex(id: Long): Book {
        books.find(id) ?: notFound("book $id not found")
        // the old passages stay searchable until the new ones replace them
        books.requeue(id)
        ingest.enqueue(id)
        return books.find(id)!!.book
    }
}

/** Published after a library sync deleted [count] books (and, by cascade, their passages). */
data class BooksRemoved(val count: Int)
