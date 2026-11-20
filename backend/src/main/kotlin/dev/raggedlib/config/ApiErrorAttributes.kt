package dev.raggedlib.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.boot.webmvc.error.DefaultErrorAttributes
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import org.springframework.web.context.request.WebRequest

/**
 * The body of errors Spring MVC never saw, rendered by Boot's error page. Tomcat refuses a multipart upload over
 * Spring's limits while it parses the body, before any controller (or ApiExceptionHandler) runs; its 413 then says
 * which limit, in `error`, where clients read the message.
 */
@Component
class ApiErrorAttributes(
    @Value("\${spring.servlet.multipart.max-file-size:500MB}") private val maxFileSize: DataSize,
    @Value("\${spring.servlet.multipart.max-request-size:2GB}") private val maxRequestSize: DataSize,
) : DefaultErrorAttributes() {
    override fun getErrorAttributes(request: WebRequest, options: ErrorAttributeOptions): MutableMap<String, Any?> {
        val attributes = super.getErrorAttributes(request, options)
        if (attributes["status"] == HttpStatus.PAYLOAD_TOO_LARGE.value()) {
            attributes["error"] = uploadLimitMessage(getError(request) ?: IllegalStateException(), maxFileSize, maxRequestSize)
        }
        return attributes
    }
}
