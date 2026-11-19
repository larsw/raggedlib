package dev.raggedlib.calibre

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommentTextTest {
    @Test
    fun `paragraphs, line breaks and lists become text`() {
        val html = "<div><p>Learn <b>Rust</b> &amp; Go.</p><p>Covers:</p><ul><li>ownership</li><li>traits</li></ul>" +
            "<p>Line one<br/>line&nbsp;two</p></div>"
        assertEquals("Learn Rust & Go.\nCovers:\n• ownership\n• traits\n\nLine one\nline two", CommentText.toPlainText(html))
    }

    @Test
    fun `scripts and styles are dropped, plain text passes through`() {
        assertEquals("Hello", CommentText.toPlainText("<style>p{color:red}</style><script>alert(1)</script><p>Hello</p>"))
        assertEquals("Just text.", CommentText.toPlainText("Just text."))
    }

    @Test
    fun `simple paragraphs are safe to round-trip but rich markup is detected`() {
        assertFalse(CommentText.hasFormatting("<p>First line<br/>second line</p>"))
        assertTrue(CommentText.hasFormatting("<p><strong>Important</strong></p>"))
        assertTrue(CommentText.hasFormatting("<p style=\"color:red\">Styled</p>"))
        assertEquals("<p>First &amp; second<br>line</p><p>Next</p>", CommentText.toSimpleHtml("First & second\nline\n\nNext"))
    }
}
