package dev.raggedlib.library

import dev.raggedlib.calibre.CalibreMetadataSource
import dev.raggedlib.calibre.Formats
import dev.raggedlib.ingest.ChunkStore
import dev.raggedlib.ingest.IngestService
import dev.raggedlib.config.notFound
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class LibraryService(
    private val calibre: CalibreMetadataSource,
    private val books: BookRepository,
    private val chunks: ChunkStore,
    private val ingest: IngestService,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val syncLock = Any()

    fun sync(): SyncResult = synchronized(syncLock) {
        val snapshot = calibre.load()
        val existing = books.findAll().associateBy { it.book.id }
        var added = 0
        var updated = 0
        tx.executeWithoutResult {
            for (r in snapshot.records) {
                val old = existing[r.id]
                val preferred = Formats.choose(r.formats.keys)
                if (old == null) {
                    books.insert(r, preferred)
                    added++
                    continue
                }
                val changed = old.lastModified != r.lastModified || old.formatPaths.keys != r.formats.keys
                val pathsMoved = old.formatPaths != r.formats || old.coverPath != r.coverPath
                if (changed) {
                    chunks.deleteForBook(r.id)
                    books.updateMetadata(r, preferred, reset = true)
                    updated++
                } else if (pathsMoved) {
                    books.updateMetadata(r, old.book.sourceFormat ?: preferred, reset = false)
                }
            }
        }
        val seen = snapshot.records.mapTo(HashSet()) { it.id }
        val gone = existing.keys.filter { it !in seen }
        val removed = books.delete(gone) // chunks go via ON DELETE CASCADE
        log.info("Library sync via {}: +{} ~{} -{} (total {})", snapshot.source, added, updated, removed, snapshot.records.size)
        SyncResult(added, updated, removed, snapshot.records.size, snapshot.source)
    }

    fun reindex(id: Long): Book {
        books.find(id) ?: notFound("book $id not found")
        tx.executeWithoutResult {
            chunks.deleteForBook(id)
            books.resetToPending(id)
        }
        ingest.enqueue(id)
        return books.find(id)!!.book
    }
}
