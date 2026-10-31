package dev.raggedlib.calibre

import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/** One book as described by calibre. All paths are relative to the library root ('/'-separated). */
data class CalibreRecord(
    val id: Long,
    val title: String,
    val authors: List<String>,
    val tags: List<String>,
    val series: String?,
    val seriesIndex: Double?,
    val languages: List<String>,
    val pubdate: Instant?,
    /** upper-case format -> relative file path */
    val formats: Map<String, String>,
    val coverPath: String?,
    /** truncated to seconds so calibredb and metadata.db readings compare equal */
    val lastModified: Instant?,
)

object CalibreDates {
    /** Parses calibre timestamps ("2023-09-01T18:57:42+00:00" or "2023-09-01 18:57:42.442442+00:00"). */
    fun parse(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(raw.trim().replace(' ', 'T')).toInstant()
        } catch (_: Exception) {
            try {
                Instant.parse(raw.trim().replace(' ', 'T'))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun lastModified(raw: String?): Instant? = parse(raw)?.truncatedTo(ChronoUnit.SECONDS)

    /** calibre uses 0101-01-01 as "undefined" pubdate. */
    fun pubdate(raw: String?): Instant? = parse(raw)?.takeIf { it.atOffset(java.time.ZoneOffset.UTC).year > 1000 }
}
