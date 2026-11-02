package dev.raggedlib.extract

import dev.raggedlib.config.RaggedlibProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

sealed interface Extraction {
    /** [ocrPages]: pages whose text came from OCR. */
    data class Text(val paragraphs: List<Paragraph>, val ocrPages: Int = 0) : Extraction
    data class NoText(val reason: String) : Extraction
}

class ExtractionException(message: String) : RuntimeException(message)

/** Progress of a book's OCR: [done] of [total] pages. */
fun interface OcrProgress {
    fun update(done: Int, total: Int)
}

@Component
class TextExtractor(
    private val props: RaggedlibProperties,
    private val runner: ProcessRunner,
    private val ocr: PdfOcr,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [languages]: the book's Calibre languages, which OCR reads in besides the configured ones. */
    fun extract(file: Path, format: String, languages: List<String> = emptyList(), progress: OcrProgress? = null): Extraction {
        if (!file.exists()) throw ExtractionException("file not found: $file")
        return if (format.equals("PDF", ignoreCase = true)) pdf(file, languages, progress) else ebook(file, format)
    }

    /** Why PDFs without a text layer can't be OCR'd, or null when they can. */
    fun ocrUnavailable(): String? = ocr.unavailable()

    private fun postProcess(paragraphs: List<Paragraph>, tocPages: Set<Int> = emptySet()): List<Paragraph> =
        Structure.inferSections(Structure.mergeBroken(paragraphs, props.chunking.maxChars * 4), tocPages)

    private fun pdf(file: Path, languages: List<String>, progress: OcrProgress?): Extraction {
        Files.createDirectories(props.tmpDir)
        val out = Files.createTempFile(props.tmpDir, "pdftotext-", ".txt")
        try {
            val r = runner.run(
                listOf(props.tools.pdftotext, "-enc", "UTF-8", file.toString(), out.toString()),
                props.tools.convertTimeout,
            )
            if (r.exitCode != 0) throw ExtractionException("pdftotext exit ${r.exitCode}: ${r.stderr.trim().take(300)}")
            val pages = PdfText.splitPages(out.readText())
            val (texts, ocrPages) = withOcr(file, pages, languages, progress)
                ?: return Extraction.NoText(ocr.unavailable()?.let { "no text layer ($it)" } ?: "no text found by OCR")
            val raw = texts.joinToString("\u000C", postfix = "\u000C")
            // contents pages are recognised before cleanup drops their dotted lines as junk
            val all = PdfText.paragraphs(raw, keepJunk = true)
            val toc = Structure.tocPages(all)
            return Extraction.Text(postProcess(all.filterNot { TextCleaner.isJunkParagraph(it.text) }, toc), ocrPages)
        } finally {
            out.deleteIfExists()
        }
    }

    /**
     * The pages' text, with OCR'd text in place of missing text layers (see [OcrText.pagesToOcr]), and how many
     * pages were replaced; null if the PDF has too little text even so. A PDF with a text layer comes back as it is
     * when OCR can't run or fails: there, OCR only adds its scanned pages.
     */
    private fun withOcr(file: Path, pages: List<String>, languages: List<String>, progress: OcrProgress?): Pair<List<String>, Int>? {
        val min = props.ingest.minLettersPerPage
        val letters = pages.map(PdfText::letterCount)
        val hasText = OcrText.hasTextLayer(letters, min)
        val asIs = if (hasText) pages to 0 else null
        if (ocr.unavailable() != null) return asIs
        // without a text layer every page short of text is a candidate; with one, only scanned pages are
        val coverage = if (!hasText) emptyMap()
        else ocr.imageCoverage(file, letters.indices.filter { letters[it] < min }.map { it + 1 })
        val wanted = OcrText.pagesToOcr(letters, min, hasText, coverage, props.ocr.minImageCoverage)
        if (wanted.isEmpty()) return asIs
        val started = System.nanoTime()
        progress?.update(0, wanted.size)
        val recognised = try {
            ocr.recognize(file, wanted, languages) { done -> progress?.update(done, wanted.size) }
        } catch (e: ExtractionException) {
            if (!hasText) throw e
            log.warn("OCR of the scanned pages of {} failed; indexing its text layer only: {}", file.fileName, e.message)
            return asIs
        }
        // Next to a text layer, a scanned page must turn out to be a page of text; what OCR finds on the others
        // (blank pages, part titles, library stamps) is noise there. A scanned book keeps its short pages: a title
        // page, a chapter's last lines.
        val minLetters = if (hasText) min else 1
        val accepted = recognised.filter { (_, p) -> p.confidence >= props.ocr.minConfidence && PdfText.letterCount(p.text) >= minLetters }
        val dropped = recognised.keys.filter { it !in accepted }.sorted().joinToString { p ->
            val page = recognised.getValue(p)
            val n = PdfText.letterCount(page.text)
            when {
                n == 0 -> "$p (blank)"
                n < minLetters -> "$p ($n letters)"
                else -> "$p (confidence %.0f)".format(page.confidence)
            }
        }
        log.info(
            "OCR of {}: {} pages in {} s, {} kept (mean word confidence {}){}", file.fileName, wanted.size,
            (System.nanoTime() - started) / 1_000_000_000, accepted.size,
            recognised.values.map { it.confidence }.average().takeUnless { it.isNaN() }?.let { "%.0f".format(it) } ?: "-",
            if (dropped.isEmpty()) "" else "; dropped pages $dropped",
        )
        val texts = pages.mapIndexed { i, text -> accepted[i + 1]?.text ?: text }
        if (texts.sumOf(PdfText::letterCount) < OcrText.MIN_LETTERS) return asIs
        return texts to accepted.size
    }

    private fun ebook(file: Path, format: String): Extraction {
        Files.createDirectories(props.tmpDir)
        val dir = Files.createTempDirectory(props.tmpDir, "ebook-convert-")
        try {
            // ebook-convert picks the input plugin by extension; the calibre file already has the right one
            val out = dir.resolve("book.txt")
            val r = runner.run(
                listOf(
                    props.tools.ebookConvert, file.toString(), out.toString(),
                    "--txt-output-formatting=markdown", "--txt-output-encoding=utf-8",
                ),
                props.tools.convertTimeout,
                workDir = dir,
            )
            if (r.exitCode != 0 || !out.exists()) {
                val msg = (r.stderr.ifBlank { r.stdout }).trim().lines().lastOrNull { it.isNotBlank() } ?: "no output"
                throw ExtractionException("ebook-convert ($format) exit ${r.exitCode}: ${msg.take(300)}")
            }
            val raw = out.readText()
            if (raw.count { it.isLetter() } < 500) return Extraction.NoText("no extractable text")
            return Extraction.Text(postProcess(Structure.dropRepeatedLines(MarkdownText.paragraphs(raw))))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
