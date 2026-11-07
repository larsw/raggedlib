package dev.raggedlib.mcp

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.ApiScope
import dev.raggedlib.auth.Caller
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpSyncListFilter
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.ai.mcp.customizer.McpStatelessSyncServerCustomizer
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

/**
 * The MCP endpoint (`/mcp`) is authenticated by Spring Security like the rest of the API (SecurityConfig: API keys
 * only). Each tool needs a scope ([TOOL_SCOPES]), which it checks itself, and `tools/list` shows a key only the tools
 * it may call: a system-wide key, which acts as no user, never sees the favorites and reviews tools.
 *
 * In a servlet app, Spring AI runs stateless tools on the request thread (immediate execution), so the security
 * context is there when a tool runs. The tool list filter gets the caller through the transport context instead,
 * which the transport fills on the request thread.
 */
@Configuration
class McpSecurity {
    /** Spring AI's stateless transport, plus the caller in the transport context. */
    @Bean
    fun webMvcStatelessServerTransport(
        @Qualifier("mcpServerJsonMapper") jsonMapper: JsonMapper,
        props: McpServerStreamableHttpProperties,
        access: Access,
    ): WebMvcStatelessServerTransport = WebMvcStatelessServerTransport.builder()
        .jsonMapper(JacksonMcpJsonMapper(jsonMapper))
        .messageEndpoint(props.mcpEndpoint)
        .contextExtractor { _ ->
            access.callerOrNull()?.let { McpTransportContext.create(mapOf(CALLER to it)) } ?: McpTransportContext.EMPTY
        }
        .build()

    @Bean
    fun mcpToolFilter() = McpStatelessSyncServerCustomizer { spec ->
        spec.addToolFilter(McpSyncListFilter<McpSchema.Tool> { context, tool -> visible(context.get(CALLER) as? Caller, tool.name()) })
    }

    companion object {
        private const val CALLER = "raggedlib.caller"

        /** The scope each tool needs. */
        val TOOL_SCOPES: Map<String, ApiScope> = mapOf(
            "search_library" to ApiScope.LIBRARY_READ,
            "ask_library" to ApiScope.LIBRARY_READ,
            "list_books" to ApiScope.LIBRARY_READ,
            "list_libraries" to ApiScope.LIBRARY_READ,
            "list_tags" to ApiScope.LIBRARY_READ,
            "library_status" to ApiScope.LIBRARY_READ,
            "list_favorite_lists" to ApiScope.FAVORITES_READ,
            "get_favorite_list" to ApiScope.FAVORITES_READ,
            "create_favorite_list" to ApiScope.FAVORITES_WRITE,
            "add_to_favorite_list" to ApiScope.FAVORITES_WRITE,
            "remove_from_favorite_list" to ApiScope.FAVORITES_WRITE,
            "get_book_reviews" to ApiScope.REVIEWS_READ,
            "list_my_reviews" to ApiScope.REVIEWS_READ,
            "write_book_review" to ApiScope.REVIEWS_WRITE,
        )

        /** Whether [caller] may see (and call) tool [name]; unknown tools are hidden. */
        fun visible(caller: Caller?, name: String): Boolean {
            val scope = TOOL_SCOPES[name] ?: return false
            if (caller == null || scope !in caller.scopes) return false
            return !scope.perUser || caller.userId != null
        }
    }
}
