package dev.raggedlib.calibre

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
