package dev.raggedlib.extract

import dev.raggedlib.config.RaggedlibProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

sealed interface Extraction {
    data class Text(val paragraphs: List<Paragraph>) : Extraction
    data class NoText(val reason: String) : Extraction
}

class ExtractionException(message: String) : RuntimeException(message)

@Component
class TextExtractor(
    private val props: RaggedlibProperties,
    private val runner: ProcessRunner,
) {
    fun extract(file: Path, format: String): Extraction {
        if (!file.exists()) throw ExtractionException("file not found: $file")
        return if (format.equals("PDF", ignoreCase = true)) pdf(file) else ebook(file, format)
    }

    private fun postProcess(paragraphs: List<Paragraph>, tocPages: Set<Int> = emptySet()): List<Paragraph> =
        Structure.inferSections(Structure.mergeBroken(paragraphs, props.chunking.maxChars * 4), tocPages)

    private fun pdf(file: Path): Extraction {
        Files.createDirectories(props.tmpDir)
        val out = Files.createTempFile(props.tmpDir, "pdftotext-", ".txt")
        try {
            val r = runner.run(
                listOf(props.tools.pdftotext, "-enc", "UTF-8", file.toString(), out.toString()),
                props.tools.convertTimeout,
            )
            if (r.exitCode != 0) throw ExtractionException("pdftotext exit ${r.exitCode}: ${r.stderr.trim().take(300)}")
            val raw = out.readText()
            val pages = PdfText.splitPages(raw).size.coerceAtLeast(1)
            val letters = PdfText.letterCount(raw)
            if (letters < 500 || letters / pages < props.ingest.minLettersPerPage) return Extraction.NoText("no text layer")
            // contents pages are recognised before cleanup drops their dotted lines as junk
            val all = PdfText.paragraphs(raw, keepJunk = true)
            val toc = Structure.tocPages(all)
            return Extraction.Text(postProcess(all.filterNot { TextCleaner.isJunkParagraph(it.text) }, toc))
        } finally {
            out.deleteIfExists()
        }
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
