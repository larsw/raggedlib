package dev.raggedlib.search

import dev.raggedlib.config.RaggedlibProperties
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RerankerTest {
    private fun reranker(
        cfg: RaggedlibProperties.Rerank = RaggedlibProperties.Rerank(),
    ): Pair<Reranker, MockRestServiceServer> {
        val builder = RestClient.builder().baseUrl("http://reranker")
        val server = MockRestServiceServer.bindTo(builder).build()
        return Reranker(cfg, builder.build()) to server
    }

    private fun json(body: String) = withSuccess(body, MediaType.APPLICATION_JSON)

    @Test
    fun `maps results back to input order and applies the sigmoid`() {
        val (r, server) = reranker()
        server.expect(requestTo("http://reranker/v1/rerank")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"query":"q","documents":["d0","d1","d2"],"top_n":3}"""))
            .andRespond(json("""{"results":[{"index":2,"relevance_score":0.0},{"index":0,"relevance_score":7.5},
                                            {"index":1,"relevance_score":-11.0}]}"""))
        val s = r.score("q", listOf("d0", "d1", "d2"))!!
        assertContentEquals(doubleArrayOf(Reranker.sigmoid(7.5), Reranker.sigmoid(-11.0), 0.5), s)
        server.verify()
    }

    @Test
    fun `passes probabilities through when sigmoid is off`() {
        val (r, server) = reranker(RaggedlibProperties.Rerank(sigmoid = false))
        server.expect(requestTo("http://reranker/v1/rerank"))
            .andRespond(json("""{"results":[{"index":0,"relevance_score":0.25}]}"""))
        assertContentEquals(doubleArrayOf(0.25), r.score("q", listOf("d0")))
    }

    @Test
    fun `server errors and malformed answers give null so search keeps the fused order`() {
        val (r, server) = reranker(RaggedlibProperties.Rerank(cooldown = Duration.ZERO))
        server.expect(requestTo("http://reranker/v1/rerank")).andRespond(withServerError())
        assertNull(r.score("q", listOf("d0")))
        server.reset()
        // missing index, non-numeric score, missing score, duplicate index, out of range, wrong count, no results
        for (bad in listOf(
            """{"results":[{"relevance_score":1.0},{"index":1,"relevance_score":2.0}]}""",
            """{"results":[{"index":0,"relevance_score":"x"},{"index":1,"relevance_score":1.0}]}""",
            """{"results":[{"index":0},{"index":1,"relevance_score":1.0}]}""",
            """{"results":[{"index":0,"relevance_score":1.0},{"index":0,"relevance_score":2.0}]}""",
            """{"results":[{"index":0,"relevance_score":1.0},{"index":5,"relevance_score":2.0}]}""",
            """{"results":[{"index":0,"relevance_score":1.0}]}""",
            """{"error":"no"}""",
        )) {
            server.expect(requestTo("http://reranker/v1/rerank")).andRespond(json(bad))
            assertNull(r.score("q", listOf("d0", "d1")), bad)
            server.reset()
        }
    }

    @Test
    fun `no documents means no call`() {
        val (r, server) = reranker()
        assertContentEquals(DoubleArray(0), r.score("q", emptyList()))
        server.verify()
    }

    @Test
    fun `after a failure the reranker is skipped for the cooldown instead of waiting out a timeout each time`() {
        val (r, server) = reranker(RaggedlibProperties.Rerank(cooldown = Duration.ofMinutes(5)))
        server.expect(requestTo("http://reranker/v1/rerank")).andRespond(withServerError())
        assertNull(r.score("q", listOf("d0")))
        // a second request would fail the test ("no further requests expected" is an AssertionError, not caught)
        assertNull(r.score("q", listOf("d0")))
        server.verify()
    }

    @Test
    fun `a search that can't get a reranker slot keeps the fused order without calling`() {
        val (r, server) = reranker(RaggedlibProperties.Rerank(maxConcurrent = 1, queueWait = Duration.ZERO))
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.expect(requestTo("http://reranker/v1/rerank")).andRespond { req ->
            inside.countDown()
            release.await(10, TimeUnit.SECONDS)
            json("""{"results":[{"index":0,"relevance_score":0.0}]}""").createResponse(req)
        }
        val first = Executors.newVirtualThreadPerTaskExecutor().use { exec ->
            val f = exec.submit<DoubleArray?> { r.score("q", listOf("d0")) }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            assertNull(r.score("q", listOf("d0")))   // the only slot is taken
            release.countDown()
            f.get()
        }
        assertContentEquals(doubleArrayOf(0.5), first)
        server.verify()
    }
}
