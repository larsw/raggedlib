# Connecting a harness to raggedlib's MCP server

The server: `http://localhost:8080/mcp` by default (a backend run on the host), or `http://localhost:3000/mcp`
through the web UI (the docker-compose stack, which also publishes the backend itself at `:8090/mcp`); streamable
HTTP in stateless mode (every request is a plain POST; no session, no SSE stream to keep open). Every request needs
an API key, as `Authorization: Bearer <key>` or `X-API-Key: <key>`.

The key belongs to the user: they mint it in the raggedlib web UI (their name in the navbar → API keys), choosing
its scopes, and it is shown only once. Ask them for it; never invent or look for one. `library:read` covers the
search tools; `favorites:*` and `reviews:*` add the tools for their favorite lists and reviews. An admin may give
out a system-wide key instead, which has the search tools only. Prefer configurations that reference an environment
variable over ones that store the key itself.

## Check the server first

```bash
curl -fsS http://localhost:8080/actuator/health          # {"status":"UP"} when the backend runs
RAGGEDLIB_MCP_KEY=... python3 scripts/raggedlib_mcp.py library_status
```

- Connection refused: the backend isn't running. In the raggedlib repo: `docker compose up -d`.
- 401: wrong, revoked or expired key, or its owner was disabled. The user can mint a new one in the web UI.
- A tool is missing from the tool list, or fails with a scope error: the key lacks that tool's scope.
- Empty searches everywhere: `library_status` shows whether any books are indexed yet, and `list_libraries` which
  libraries the key can see (a user only sees the libraries they have a role in).

## Claude Code

```bash
claude mcp add --transport http raggedlib http://localhost:8080/mcp --header "Authorization: Bearer $RAGGEDLIB_MCP_KEY"
```

Add `--scope user` to have it in every project. `ask_library` runs for up to ~150 s, longer than the default MCP
tool timeout, so start Claude Code with `MCP_TOOL_TIMEOUT=180000` (or set it under `env` in settings.json).
Restart the session after adding the server; `/mcp` shows whether it connected.

## Codex CLI

In `~/.codex/config.toml`:

```toml
[mcp_servers.raggedlib]
url = "http://localhost:8080/mcp"
bearer_token_env_var = "RAGGEDLIB_MCP_KEY"
tool_timeout_sec = 180
```

## Other harnesses

Most accept a JSON entry of this shape (the key's location varies: `mcpServers`, `servers`, `mcp.servers`):

```json
{
  "raggedlib": {
    "type": "http",
    "url": "http://localhost:8080/mcp",
    "headers": { "Authorization": "Bearer <key>" }
  }
}
```

Look for the harness's per-tool timeout and set it to at least 180 s for `ask_library`. Harnesses that only
support stdio MCP servers can't connect directly; use `scripts/raggedlib_mcp.py` from a shell instead.

## Server on another machine

The server publishes its ports on localhost only. From another machine, use an SSH tunnel
(`ssh -N -L 8080:localhost:8080 <host>`) rather than exposing port 8080.
