package dev.raggedlib.mcp

import dev.raggedlib.auth.ApiScope
import dev.raggedlib.auth.Caller
import org.springframework.ai.mcp.annotation.McpTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpToolScopesTest {
    private val tools = listOf(LibraryTools::class.java, UserTools::class.java)
        .flatMap { c -> c.declaredMethods.mapNotNull { it.getAnnotation(McpTool::class.java)?.name } }

    @Test
    fun `every tool has a scope, and every scope entry is a tool`() {
        // a tool without one would be hidden from every key
        assertEquals(tools.toSortedSet(), McpSecurity.TOOL_SCOPES.keys.toSortedSet())
    }

    @Test
    fun `only per-user tools need per-user scopes, and they live in UserTools`() {
        val userTools = UserTools::class.java.declaredMethods.mapNotNull { it.getAnnotation(McpTool::class.java)?.name }.toSet()
        McpSecurity.TOOL_SCOPES.forEach { (tool, scope) -> assertEquals(tool in userTools, scope.perUser, tool) }
    }

    @Test
    fun `a key sees the tools of its scopes, a system key never the per-user ones`() {
        val personal = Caller(7, setOf(ApiScope.LIBRARY_READ, ApiScope.FAVORITES_READ), null, viaApiKey = true, label = "k")
        assertTrue(McpSecurity.visible(personal, "search_library"))
        assertTrue(McpSecurity.visible(personal, "list_favorite_lists"))
        assertFalse(McpSecurity.visible(personal, "create_favorite_list"))
        assertFalse(McpSecurity.visible(personal, "write_book_review"))
        assertFalse(McpSecurity.visible(personal, "no_such_tool"))

        // even if a system key somehow had a per-user scope, it acts as no user
        val system = Caller(null, ApiScope.entries.toSet(), null, viaApiKey = true, label = "s")
        assertTrue(McpSecurity.visible(system, "ask_library"))
        assertFalse(McpSecurity.visible(system, "list_favorite_lists"))
        assertFalse(McpSecurity.visible(system, "get_book_reviews"))
        assertFalse(McpSecurity.visible(null, "search_library"))
    }
}
