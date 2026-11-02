package dev.raggedlib.extract

import dev.raggedlib.config.RaggedlibProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * OCR of PDF pages: each page is rendered to a grayscale PNG with `pdftoppm` and read by Tesseract. Pages from all
 * books share [RaggedlibProperties.Ocr.concurrency] slots, each running one single-threaded Tesseract, so books
 * being extracted side by side don't oversubscribe the CPU.
 */
@Component
class PdfOcr(
    private val props: RaggedlibProperties,
    private val runner: ProcessRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val slots = Semaphore(props.ocr.concurrency.coerceAtLeast(1), true)

    /** Languages Tesseract has, `osd` included; null when it can't be run. Probed once. */
    private val installed: Set<String>? by lazy { probe() }

    /** Why OCR can't run, or null when it can. */
    fun unavailable(): String? = when {
        !props.ocr.enabled -> "OCR is off"
        installed == null -> "OCR unavailable: ${props.tools.tesseract} not found"
        OcrText.languages(emptyList(), props.ocr.languages, installed.orEmpty()) == null ->
            "OCR unavailable: none of the languages ${props.ocr.languages} is installed"
        else -> null
    }

    @EventListener(ApplicationReadyEvent::class)
    fun logAvailability() {
        val reason = unavailable()
        if (reason != null) {
            if (props.ocr.enabled) log.warn("{}; PDFs without a text layer will be skipped", reason)
            return
        }
        val langs = installed.orEmpty()
        val missing = props.ocr.languages.split('+', ',', ' ').filter { it.isNotBlank() && it !in langs }
        log.info("OCR: languages {} (installed: {}), {} pages at a time{}", OcrText.languages(emptyList(), props.ocr.languages, langs),
            langs.sorted().joinToString(), props.ocr.concurrency, if (missing.isEmpty()) "" else "; not installed: ${missing.joinToString()}")
    }

    /**
     * Share of each of [pages] (1-based) covered by raster images (see [OcrText.imageCoverage]); pages missing from
     * it, e.g. because the tools failed, count as not scanned. Images are listed for those pages only: listing a
     * whole book can take most of a minute, about 0.1 s per page.
     */
    fun imageCoverage(file: Path, pages: List<Int>): Map<Int, Double> {
        if (pages.isEmpty()) return emptyMap()
        val timeout = props.tools.convertTimeout
        return try {
            val info = runner.run(listOf(props.tools.pdfinfo, "-f", "${pages.min()}", "-l", "${pages.max()}", file.toString()), timeout)
            if (info.exitCode != 0) return emptyMap()
            val sizes = OcrText.pageSizes(info.stdout)
            OcrText.ranges(pages, MAX_LISTINGS).flatMap { range ->
                val images = runner.run(
                    listOf(props.tools.pdfimages, "-list", "-f", "${range.first}", "-l", "${range.last}", file.toString()), timeout,
                )
                if (images.exitCode != 0) emptyList() else OcrText.imageCoverage(images.stdout, sizes).entries.map { it.toPair() }
            }.toMap().filterKeys { it in pages }
        } catch (e: ProcessTimeoutException) {
            log.warn("Could not list the images of {}: {}", file.fileName, e.message)
            emptyMap()
        }
    }

    /**
     * Recognises [pages] (1-based) of [file], in the book's languages plus the configured ones. Pages that fail are
     * left out, unless more than a tenth of them do: then the whole book fails. [onPage] is called with the number
     * of pages done after each one.
     */
    fun recognize(file: Path, pages: List<Int>, bookLanguages: List<String>, onPage: (done: Int) -> Unit = {}): Map<Int, OcrPage> {
        val langs = installed?.let { OcrText.languages(bookLanguages, props.ocr.languages, it) }
            ?: throw ExtractionException(unavailable() ?: "OCR unavailable")
        val psm = if ("osd" in installed.orEmpty()) "1" else "3" // 1 = automatic layout plus orientation detection
        val results = ConcurrentHashMap<Int, OcrPage>()
        val errors = ConcurrentLinkedQueue<String>()
        val done = AtomicInteger()
        Executors.newVirtualThreadPerTaskExecutor().use { exec ->
            pages.forEach { p ->
                exec.submit {
                    slots.acquire()
                    try {
                        results[p] = page(file, p, langs, psm)
                    } catch (e: Exception) {
                        errors += "page $p: ${e.message}"
                    } finally {
                        slots.release()
                    }
                    onPage(done.incrementAndGet())
                }
            }
        }
        if (errors.size > maxOf(2, pages.size / 10)) {
            throw ExtractionException("OCR failed on ${errors.size} of ${pages.size} pages; ${errors.first()}")
        }
        if (errors.isNotEmpty()) log.warn("OCR of {} skipped {} of {} pages: {}", file.fileName, errors.size, pages.size, errors.first())
        return results
    }

    private fun page(file: Path, page: Int, langs: String, psm: String): OcrPage {
        Files.createDirectories(props.tmpDir)
        val dir = Files.createTempDirectory(props.tmpDir, "ocr-")
        try {
            val render = runner.run(
                listOf(
                    props.tools.pdftoppm, "-r", props.ocr.dpi.toString(), "-gray", "-png", "-singlefile",
                    "-f", page.toString(), "-l", page.toString(), file.toString(), dir.resolve("page").toString(),
                ),
                props.ocr.pageTimeout,
            )
            if (render.exitCode != 0 || !dir.resolve("page.png").exists()) {
                throw ExtractionException("pdftoppm exit ${render.exitCode}: ${render.stderr.trim().take(200)}")
            }
            val ocr = runner.run(
                listOf(props.tools.tesseract, "page.png", "out", "-l", langs, "--psm", psm, "txt", "tsv"),
                props.ocr.pageTimeout,
                workDir = dir,
                // one thread per Tesseract; the slots provide the parallelism
                env = mapOf("OMP_THREAD_LIMIT" to "1"),
            )
            val txt = dir.resolve("out.txt")
            if (ocr.exitCode != 0 || !txt.exists()) {
                throw ExtractionException("tesseract exit ${ocr.exitCode}: ${ocr.stderr.trim().lines().lastOrNull().orEmpty().take(200)}")
            }
            val tsv = dir.resolve("out.tsv")
            return OcrPage(txt.readText(), if (tsv.exists()) OcrText.confidence(tsv.readText()) else 0.0)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        /** pdfimages runs per book at most; candidate pages scattered wider are listed in fewer, longer ranges. */
        const val MAX_LISTINGS = 20
    }

    private fun probe(): Set<String>? = try {
        val r = runner.run(listOf(props.tools.tesseract, "--list-langs"), props.ocr.pageTimeout)
        if (r.exitCode == 0) OcrText.installedLanguages(r.stdout.ifBlank { r.stderr }) else null
    } catch (e: Exception) {
        log.debug("tesseract not runnable: {}", e.message)
        null
    }
}
