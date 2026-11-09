package dev.raggedlib.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
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

class ApiException(val status: HttpStatus, message: String) : RuntimeException(message)

fun notFound(message: String): Nothing = throw ApiException(HttpStatus.NOT_FOUND, message)
fun badRequest(message: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, message)

data class ErrorBody(val error: String)

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = ResponseEntity.status(e.status).body(ErrorBody(e.message ?: e.status.reasonPhrase))

    @ExceptionHandler(ResponseStatusException::class)
    fun rse(e: ResponseStatusException) =
        ResponseEntity.status(e.statusCode).body(ErrorBody(e.reason ?: e.statusCode.toString()))

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
