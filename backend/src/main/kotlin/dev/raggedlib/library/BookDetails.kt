package dev.raggedlib.library

import dev.raggedlib.calibre.CalibreExtras
import dev.raggedlib.calibre.MetadataDbReader
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.notFound
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service

/** GET /api/books/{id}/details: everything the Library's detail panel shows. */
data class BookDetails(
    val book: Book,
    /** From Calibre's metadata.db; null when it can't be read (e.g. metadata comes from calibredb only). */
    val calibre: CalibreExtras?,
    /** Status DUPLICATE: the book standing in for this one. */
    val duplicateOf: BookRef?,
    /** Indexed sections in reading order, with their first page (PDFs) and number of passages. */
    val sections: List<SectionInfo>,
)

data class BookRef(val id: Long, val title: String)

data class SectionInfo(val title: String, val firstPage: Int?, val passages: Int)

@Service
class BookDetailsService(
    private val books: BookRepository,
    private val jdbc: NamedParameterJdbcTemplate,
    props: RaggedlibProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val metadataDb = MetadataDbReader(props.libraryPath)

    fun details(id: Long): BookDetails {
        val book = (books.find(id) ?: notFound("book $id not found")).book
        val extras = try {
            metadataDb.extras(id)
        } catch (e: Exception) {
            log.debug("No Calibre extras for book {}: {}", id, e.message)
            null
        }
        val keeper = book.duplicateOf?.let { books.find(it)?.book }?.let { BookRef(it.id, it.title) }
        return BookDetails(book, extras, keeper, sections(id))
    }

    /** Sections in reading order: by first page where there are pages, else by first passage. */
    private fun sections(id: Long): List<SectionInfo> = jdbc.query(
        """
        select section, min(page) as first_page, count(*) as passages
        from vector_store where book_id = :id and section is not null and section <> ''
        group by section
        order by min(page) nulls last, min(chunk_index)
        limit :limit
        """.trimIndent(),
        mapOf("id" to id, "limit" to MAX_SECTIONS),
    ) { rs, _ -> SectionInfo(rs.getString(1), rs.getObject(2) as Int?, rs.getInt(3)) }

    private companion object {
        const val MAX_SECTIONS = 500
    }
}
