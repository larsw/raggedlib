package dev.raggedlib.extract

import dev.raggedlib.config.RaggedlibProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

class ProcessTimeoutException(message: String) : RuntimeException(message)

/** Runs external tools with output captured to temp files (no pipe deadlocks) and a hard timeout. */
@Component
class ProcessRunner(private val props: RaggedlibProperties) {

    fun run(command: List<String>, timeout: Duration, workDir: Path? = null): ProcessResult {
        Files.createDirectories(props.tmpDir)
        val out = Files.createTempFile(props.tmpDir, "raggedlib-out-", ".txt")
        val err = Files.createTempFile(props.tmpDir, "raggedlib-err-", ".txt")
        try {
            val pb = ProcessBuilder(command)
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            if (workDir != null) pb.directory(workDir.toFile())
            val process = pb.start()
            val finished = try {
                process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                kill(process)
                throw e
            }
            if (!finished) {
                kill(process)
                throw ProcessTimeoutException("${command.first()} timed out after ${timeout.toSeconds()}s")
            }
            return ProcessResult(process.exitValue(), out.readText(), err.readText().takeLast(4000))
        } finally {
            out.deleteIfExists()
            err.deleteIfExists()
        }
    }

    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }
}
