package dev.raggedlib.config

import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException
import org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException
import org.springframework.util.unit.DataSize
import org.springframework.web.multipart.MaxUploadSizeExceededException
import kotlin.test.Test
import kotlin.test.assertEquals

class UploadLimitMessageTest {
    private val file = DataSize.ofMegabytes(500)
    private val request = DataSize.ofGigabytes(2)

    /** As StandardMultipartHttpServletRequest wraps Tomcat's failure. */
    private fun tooLarge(cause: Exception) = MaxUploadSizeExceededException(-1, IllegalStateException(cause))

    @Test
    fun `names the per-file limit when one file is too large`() {
        val e = tooLarge(FileSizeLimitExceededException("The field files exceeds its maximum permitted size", 600, 500))
        assertEquals("a file is larger than the 500 MB limit for one file", uploadLimitMessage(e, file, request))
    }

    @Test
    fun `names the request limit when the whole upload is too large`() {
        val e = tooLarge(SizeLimitExceededException("the request was rejected because its size exceeds the maximum", 3, 2))
        assertEquals("the upload is larger than the 2 GB limit for one request", uploadLimitMessage(e, file, request))
    }

    @Test
    fun `names both limits when the cause is unknown`() {
        assertEquals(
            "the upload is too large: each file can be up to 500 MB, and one request up to 2 GB",
            uploadLimitMessage(MaxUploadSizeExceededException(-1), file, request),
        )
    }
}

class ApiErrorAttributesTest {
    @Test
    fun `a 413 from the servlet container says which limit it hit`() {
        val request = org.springframework.mock.web.MockHttpServletRequest().apply {
            setAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 413)
            setAttribute(
                jakarta.servlet.RequestDispatcher.ERROR_EXCEPTION,
                IllegalStateException(FileSizeLimitExceededException("too big", 600, 500)),
            )
        }
        val attributes = ApiErrorAttributes(DataSize.ofMegabytes(500), DataSize.ofGigabytes(2)).getErrorAttributes(
            org.springframework.web.context.request.ServletWebRequest(request),
            org.springframework.boot.web.error.ErrorAttributeOptions.defaults(),
        )
        assertEquals(413, attributes["status"])
        assertEquals("a file is larger than the 500 MB limit for one file", attributes["error"])
    }
}
