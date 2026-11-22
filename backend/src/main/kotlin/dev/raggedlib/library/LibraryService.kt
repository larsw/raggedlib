package dev.raggedlib.library

import dev.raggedlib.calibre.CalibreMetadataSource
import dev.raggedlib.calibre.CalibreBookIdentity
import dev.raggedlib.calibre.CalibreLibraryWriteLock
import dev.raggedlib.calibre.Formats
import dev.raggedlib.ingest.IngestService
import dev.raggedlib.config.notFound
import org.slf4j.LoggerFactory
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
    private val deletions: BookDeletionService,
    private val writeLock: CalibreLibraryWriteLock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val syncLock = Any()

    /**
     * Runs [block] (a sync of [lib]) under the library's write lock, then [syncLock]: an edit landing between reading
     * Calibre and reconciling would otherwise be overwritten by the older snapshot (and its book queued again). Always
     * in this order; resuming editing already holds the write lock when it syncs.
     */
    private fun <T> syncing(lib: LibraryInfo, block: () -> T): T =
        writeLock.withLibrary(lib.root) { synchronized(syncLock) { block() } }

    /**
     * Syncs the configured libraries (only [libraryIds], if given); one library's failure (calibredb down, path
     * unmounted) doesn't block the rest.
     */
    fun sync(libraryIds: Set<Long>? = null): SyncResult {
        var added = 0
        var updated = 0
        var removed = 0
        var total = 0
        val sources = LinkedHashSet<String>()
        for (lib in registry.all) {
            if (libraryIds != null && lib.id !in libraryIds) continue
            try {
                val (a, u, r, t, source) = syncing(lib) { syncOne(lib) }
                added += a; updated += u; removed += r; total += t
                sources += source
            } catch (e: Exception) {
                log.warn("Sync failed for library '{}' ({}): {}", lib.name, lib.root, e.message)
            }
        }
        return SyncResult(added, updated, removed, total, sources.joinToString("+").ifEmpty { SyncResult.NOTHING_SYNCED })
    }

    /** Syncs one library and returns the Calibre records identified by [calibreIds], propagating sync failures. */
    fun syncAndFindBooks(libraryId: Long, calibreIds: Set<Long>): Map<Long, Book> {
        if (calibreIds.isEmpty()) return emptyMap()
        val lib = registry.byId(libraryId)
        return syncing(lib) {
            syncOne(lib)
            val byCalibreId = books.findAllByLibrary(libraryId).associate { it.book.calibreId to it.book }
            calibreIds.associateWith { calibreId ->
                byCalibreId[calibreId] ?: error("Calibre added book $calibreId, but sync did not find it in library '${lib.name}'")
            }
        }
    }

    fun syncAndFindMatchingBooks(libraryId: Long, identity: CalibreBookIdentity): List<Book> {
        val lib = registry.byId(libraryId)
        return syncing(lib) { matching(lib, identity) }
    }

    private fun matching(lib: LibraryInfo, identity: CalibreBookIdentity): List<Book> {
        syncOne(lib)
        val libraryId = lib.id
        val title = normalizeIdentity(identity.title)
        val authors = identity.authors.map(::normalizeIdentity).sorted()
        return books.findAllByLibrary(libraryId).map { it.book }.filter { book ->
            normalizeIdentity(book.title) == title && book.authors.map(::normalizeIdentity).sorted() == authors
        }
    }

    private fun normalizeIdentity(value: String) =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun syncOne(lib: LibraryInfo): SyncResult {
        deletions.purgeExpiredIn(lib.id)
        val snapshot = calibre.load(lib.root)
        val existing = books.findAllIncludingDeletedByLibrary(lib.id).associateBy { it.book.calibreId }
        var added = 0
        var updated = 0
        var restored = 0
        tx.executeWithoutResult {
            for (r in snapshot.records) {
                val old = existing[r.id]
                val preferred = Formats.choose(r.formats.keys)
                if (old == null) {
                    books.insert(lib.id, r, preferred)
                    added++
                    continue
                }
                var restoredNow = false
                if (old.deletedAt != null) {
                    if (books.restore(old.book.id)) {
                        restored++
                        restoredNow = true
                    } else if (books.find(old.book.id) == null) {
                        books.insert(lib.id, r, preferred)
                        added++
                        continue
                    }
                }
                // Restoring a book from Calibre's trash bumps its last_modified, though the book itself is unchanged:
                // then only different formats need re-indexing, and the rest is kept as metadata.
                val changed = old.formatPaths.keys != r.formats.keys ||
                    (old.lastModified != r.lastModified && !restoredNow)
                // also refreshes what an older index didn't store (publisher, before V7) without re-indexing
                val metadataOnly = old.lastModified != r.lastModified || old.formatPaths != r.formats ||
                    old.coverPath != r.coverPath || old.book.publisher != r.publisher
                if (changed) {
                    // passages stay searchable until re-indexing replaces them (reusing unchanged embeddings)
                    books.updateMetadata(old.book.id, r, preferred, reset = true)
                    updated++
                } else if (metadataOnly) {
                    books.updateMetadata(old.book.id, r, old.book.sourceFormat ?: preferred, reset = false)
                } else if (old.deletedAt != null) {
                    updated++
                }
            }
        }
        val seen = snapshot.records.mapTo(HashSet()) { it.id }
        val gone = existing.values.filter { it.deletedAt == null && it.book.calibreId !in seen }.map { it.book.id }
        val removed = deletions.markDeleted(gone, "Calibre sync").size
        // duplicates of removed books need passages of their own now (also catches one marked while deleting)
        val orphaned = books.requeueOrphanedDuplicates()
        if (orphaned.isNotEmpty()) log.info("Duplicates {} of removed books are pending again", orphaned)
        deletions.visibilityChanged(restored)
        log.info("Library '{}' sync via {}: +{} ~{} -{} (total {})", lib.name, snapshot.source, added, updated, removed, snapshot.records.size)
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
