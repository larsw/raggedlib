package dev.raggedlib.system

import dev.raggedlib.config.RaggedlibProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

data class SystemInfo(
    val libraryPath: String,
    val ollamaUrl: String,
    val ollamaReachable: Boolean,
    val chatModel: String,
    val embeddingModel: String,
    val embeddingDimensions: Int,
    val availableChatModels: List<String>,
)

@RestController
@RequestMapping("/api")
class SystemController(
    private val props: RaggedlibProperties,
    @Value("\${spring.ai.ollama.base-url}") private val ollamaUrl: String,
    @Value("\${spring.ai.ollama.chat.model}") private val chatModel: String,
    @Value("\${spring.ai.ollama.embedding.model}") private val embeddingModel: String,
    restClientBuilder: RestClient.Builder,
) {
    private val client = restClientBuilder.clone()
        .baseUrl(ollamaUrl)
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(2_000)
            setReadTimeout(5_000)
        })
        .build()

    @GetMapping("/system")
    fun system(): SystemInfo {
        val models = try {
            client.get().uri("/api/tags").retrieve().body(JsonNode::class.java)
        } catch (_: Exception) {
            null
        }
        val chatModels = models?.path("models")?.values()?.filter { m ->
            val caps = m.path("capabilities").values().map { it.asString() }
            val name = m.path("name").asString()
            "embedding" !in caps && !name.contains("embed", ignoreCase = true)
        }?.map { it.path("name").asString() }?.sorted() ?: emptyList()
        return SystemInfo(
            libraryPath = props.libraryPath.toString(),
            ollamaUrl = ollamaUrl,
            ollamaReachable = models != null,
            chatModel = chatModel,
            embeddingModel = embeddingModel,
            embeddingDimensions = props.embedding.dimensions,
            availableChatModels = chatModels,
        )
    }
}
