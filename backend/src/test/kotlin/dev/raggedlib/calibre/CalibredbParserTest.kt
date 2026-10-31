package dev.raggedlib.calibre

import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalibredbParserTest {
    private val json = """
        [
          {
            "authors": "Nu1l Team & Jane Doe",
            "cover": "/lib/Nu1l Team/Handbook for CTFers (2)/cover.jpg",
            "formats": [
              "/lib/Nu1l Team/Handbook for CTFers (2)/Handbook for CTFers - Nu1l Team.pdf",
              "/lib/Nu1l Team/Handbook for CTFers (2)/Handbook for CTFers - Nu1l Team.epub"
            ],
            "id": 2,
            "languages": ["eng"],
            "last_modified": "2023-09-01T18:57:42+00:00",
            "pubdate": "2022-05-05T22:00:00+00:00",
            "series": "CTF",
            "series_index": 2.0,
            "tags": ["Security", "Network Security"],
            "title": "Handbook for CTFers"
          },
          {
            "authors": "Unknown",
            "formats": [],
            "id": 9,
            "languages": [],
            "last_modified": "2024-01-02T03:04:05+00:00",
            "pubdate": "0101-01-01T00:00:00+00:00",
            "series_index": 1.0,
            "tags": [],
            "title": "Bare"
          }
        ]
    """.trimIndent()

    @Test
    fun `parses calibredb for-machine output with relative paths`() {
        val records = CalibredbParser(JsonMapper.builder().build()).parse(json, Path.of("/lib"))
        assertEquals(2, records.size)
        val r = records[0]
        assertEquals(2L, r.id)
        assertEquals("Handbook for CTFers", r.title)
        assertEquals(listOf("Nu1l Team", "Jane Doe"), r.authors)
        assertEquals(listOf("Security", "Network Security"), r.tags)
        assertEquals("CTF", r.series)
        assertEquals(2.0, r.seriesIndex)
        assertEquals(listOf("eng"), r.languages)
        assertEquals(Instant.parse("2022-05-05T22:00:00Z"), r.pubdate)
        assertEquals(Instant.parse("2023-09-01T18:57:42Z"), r.lastModified)
        assertEquals(
            mapOf(
                "PDF" to "Nu1l Team/Handbook for CTFers (2)/Handbook for CTFers - Nu1l Team.pdf",
                "EPUB" to "Nu1l Team/Handbook for CTFers (2)/Handbook for CTFers - Nu1l Team.epub",
            ),
            r.formats,
        )
        assertEquals("Nu1l Team/Handbook for CTFers (2)/cover.jpg", r.coverPath)

        val bare = records[1]
        assertNull(bare.series)
        assertNull(bare.seriesIndex)
        assertNull(bare.pubdate, "calibre's 0101 'undefined' date maps to null")
        assertNull(bare.coverPath)
        assertEquals(emptyMap(), bare.formats)
    }

    @Test
    fun `metadata db timestamps compare equal to calibredb ones`() {
        assertEquals(
            CalibreDates.lastModified("2023-09-01T18:57:42+00:00"),
            CalibreDates.lastModified("2023-09-01 18:57:42.442442+00:00"),
        )
    }
}
