package dev.raggedlib.config

import org.apache.catalina.connector.ClientAbortException
import org.springframework.http.converter.HttpMessageNotWritableException
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientDisconnectTest {
    /** The shape Tomcat + Jackson produce when the browser aborts a fetch mid-response. */
    private fun abortedWrite(): Exception {
        val reset = IOException("Connection reset by peer")
        val abort = ClientAbortException(reset)
        val notUsable = AsyncRequestNotUsableException("ServletOutputStream failed to write: $reset", abort)
        return HttpMessageNotWritableException("Could not write JSON: ${notUsable.message}", notUsable)
    }

    @Test
    fun `a client abort wrapped in a write failure is a disconnect`() {
        assertTrue(isClientDisconnect(abortedWrite()))
        assertTrue(isClientDisconnect(ClientAbortException(IOException("Broken pipe"))))
    }

    @Test
    fun `other failures are not`() {
        assertFalse(isClientDisconnect(IllegalStateException("boom", IOException("disk full"))))
        assertFalse(isClientDisconnect(HttpMessageNotWritableException("no converter")))
    }

    @Test
    fun `the handler answers nothing for a disconnect`() {
        assertNull(ApiExceptionHandler().other(abortedWrite()))
    }
}
