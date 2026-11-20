package dev.raggedlib.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.util.unit.DataSize
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * True when [e] (or anything in its cause chain) says the client closed the connection. Spring wraps these in
 * HttpMessageNotWritableException when they hit mid-serialization, so the top-level type alone doesn't tell.
 */
internal fun isClientDisconnect(e: Throwable): Boolean =
    generateSequence(e) { it.cause.takeIf { c -> c !== it } }
        .take(20)
        .any { it is AsyncRequestNotUsableException || it.javaClass.simpleName == "ClientAbortException" }

/** Tomcat's cause names the limit: FileSizeLimitExceededException (one file) or SizeLimitExceededException (all). */
internal fun uploadLimitMessage(e: Throwable, maxFileSize: DataSize, maxRequestSize: DataSize): String {
    val causes = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.take(20).toList()
    fun mentions(name: String) = causes.any { it.javaClass.simpleName == name || it.message.orEmpty().contains(name) }
    return when {
        mentions("FileSizeLimitExceededException") -> "a file is larger than the ${maxFileSize.label()} limit for one file"
        mentions("SizeLimitExceededException") -> "the upload is larger than the ${maxRequestSize.label()} limit for one request"
        else -> "the upload is too large: each file can be up to ${maxFileSize.label()}, and one request up to ${maxRequestSize.label()}"
    }
}

class ApiException(val status: HttpStatus, message: String, val details: Any? = null) : RuntimeException(message)

fun notFound(message: String): Nothing = throw ApiException(HttpStatus.NOT_FOUND, message)
fun badRequest(message: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, message)

data class ErrorBody(val error: String)
data class DetailedErrorBody(val error: String, val details: Any)

@RestControllerAdvice
class ApiExceptionHandler(
    @Value("\${spring.servlet.multipart.max-file-size:500MB}") private val maxFileSize: DataSize,
    @Value("\${spring.servlet.multipart.max-request-size:2GB}") private val maxRequestSize: DataSize,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<Any> {
        val error = e.message ?: e.status.reasonPhrase
        val body = e.details?.let { DetailedErrorBody(error, it) } ?: ErrorBody(error)
        return ResponseEntity.status(e.status).body<Any>(body)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun rse(e: ResponseStatusException) =
        ResponseEntity.status(e.statusCode).body(ErrorBody(e.reason ?: e.statusCode.toString()))

    /** Spring's multipart limits stop an upload before the services see it; say which one did. */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun uploadTooLarge(e: MaxUploadSizeExceededException) =
        ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorBody(uploadLimitMessage(e, maxFileSize, maxRequestSize)))

    @ExceptionHandler(NoResourceFoundException::class)
    fun noResource(e: NoResourceFoundException) =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorBody("not found: /${e.resourcePath}"))

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
        IllegalArgumentException::class,
    )
    fun bad(e: Exception) = ResponseEntity.badRequest().body(ErrorBody(e.message?.take(500) ?: "bad request"))

    @ExceptionHandler(Exception::class)
    fun other(e: Exception): ResponseEntity<ErrorBody>? {
        // The client went away mid-response (an aborted fetch, a closed tab): nothing to answer, and nobody to answer.
        if (isClientDisconnect(e)) {
            log.debug("Client disconnected: {}", e.message)
            return null
        }
        log.error("Unhandled error", e)
        return ResponseEntity.internalServerError().body(ErrorBody(e.message?.take(500) ?: e.javaClass.simpleName))
    }
}
