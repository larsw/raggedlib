package dev.raggedlib.mcp

import dev.raggedlib.config.RaggedlibProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath
import org.springframework.core.Ordered

@Configuration
class McpConfig {
    /** API keys guard the MCP endpoint only; the web UI's /api stays as it is (published on localhost only). */
    @Bean
    fun mcpApiKeyFilter(
        props: RaggedlibProperties,
        @Value("\${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") endpoint: String,
        @Value("\${spring.ai.mcp.server.protocol:STREAMABLE}") protocol: String,
        servletPath: DispatcherServletPath,
    ): FilterRegistrationBean<ApiKeyFilter> {
        // the filter only knows the streamable-HTTP endpoint; SSE would open /sse unguarded
        require(protocol.trim().uppercase() in setOf("STATELESS", "STREAMABLE")) {
            "spring.ai.mcp.server.protocol=$protocol: the MCP API-key filter only guards streamable HTTP (STATELESS or STREAMABLE)"
        }
        // servlet filter patterns are relative to the context, i.e. include a spring.mvc.servlet.path prefix
        val path = servletPath.getRelativePath("/" + endpoint.trim('/'))
        return FilterRegistrationBean(ApiKeyFilter(props.mcp.apiKeys)).apply {
            addUrlPatterns(path, "$path/*")
            order = Ordered.HIGHEST_PRECEDENCE + 10
            setName("mcpApiKeyFilter")
        }
    }
}
