# raggedlib

Ask questions about your Calibre ebook library. The answers cite the passages they came from: the page for PDFs, the section for ebooks. Everything runs locally on your GPU.

```
 frontend (Bun · React 19 · Blueprint 6)  :3000  ── /api proxy ──▶  backend (Kotlin · Spring Boot 4.1 · Spring AI 2.0 · JDK 25)  :8080
                                                                        │  calibredb / metadata.db (read-only)
                                                                        │  pdftotext · ebook-convert · tesseract (OCR)
                                                                        ├─▶ Postgres 18 + pgvector  :55432   (books, chunks, HNSW + full-text)
                                                                        ├─▶ Ollama (GPU)            :11434   qwen3-embedding:0.6b · qwen3.5:9b
                                                                        └─▶ llama.cpp (GPU)         :8091    bge-reranker-v2-m3 (/v1/rerank)
```

## Run it

```bash
docker compose up -d --build        # first run pulls ~7 GB of models
open http://localhost:3000          # sign in as gadmin / gadmin, then Indexing tab → Start
```

Sign-in goes through a development OpenID Connect provider, [dex](https://dexidp.io), that the stack brings along
(see [Users and roles](#users-and-roles) for its users and for using your own provider instead). For just yourself,
put `AUTH_MODE=single-user` in `.env`: no sign-in, as before raggedlib had users.

The library is mounted **read-only** from `~/Documents/CalibreLibrary`. Set `CALIBRE_LIBRARY` to use another path, and `CHAT_MODEL` or `EMBED_MODEL` to swap models (see `.env.example`). Changing the embedding model also means changing `vector(1024)` in the migration and re-indexing. Scanned PDFs are OCR'd in English; set `OCR_LANGUAGES` to read others too, e.g. `OCR_LANGUAGES=eng+nor+deu` (Tesseract's language codes), and rebuild the backend, which installs them.

### Multiple libraries

raggedlib can index and search several Calibre libraries at once, as their union by default; a chooser in the navbar
narrows the UI (and search/ask) to one. In docker-compose, add a second library by uncommenting its volume line in
`docker-compose.yml` and setting `CALIBRE_LIBRARY_1` (and optionally `CALIBRE_LIBRARY_1_NAME`) in `.env`; a third
library repeats the pattern at index 2, and so on — the same indexed-environment-variable style as `MCP_API_KEY`
(see below). Developing on the host needs no Docker volumes at all: set
`RAGGEDLIB_LIBRARIES_0_PATH=/path/to/library/one` and `RAGGEDLIB_LIBRARIES_1_PATH=/path/to/library/two` (plus
`_0_NAME` / `_1_NAME`) directly. Each library is synced, ingested and deduplicated independently, but a sync, an
indexing run or a sweep for duplicate text across libraries always covers every configured library; the chooser only
changes what's displayed. A library already indexed under the single-library `CALIBRE_LIBRARY`/`RAGGEDLIB_LIBRARY_PATH`
setting keeps its books when you add more libraries, as long as its path doesn't change.

### Develop on the host

```bash
docker compose up -d postgres ollama models reranker dex
(cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dex)   # :8080; no profile: single-user mode, no sign-in
(cd frontend && bun install && bun run dev)                          # :3000 with HMR; sign in here
```

The host backend and the containerized backend can share the same database, because file paths are stored relative to the library root. Stop the `backend` and `frontend` containers first, or they will occupy the ports.

`mvn test` includes an integration test (`AuthIntegrationTest`) that signs in through dex and exercises roles, lists,
reviews and API keys over HTTP and MCP; it starts Postgres and dex with Testcontainers, and is skipped without Docker.

## Users and roles

Users sign in with OpenID Connect (Spring Security's OAuth2 login). The web UI's server proxies `/api` and `/mcp`, so
the whole sign-in happens on its origin, and the provider redirects back to `<web UI>/api/auth/callback/oidc`.

Every user has a role per library, and sees only the libraries where it has one:

| Role | May |
|---|---|
| Viewer | browse, search, ask and read the library's books; review them and put them on favorite lists |
| Curator | also sync the library, index and re-index its books, and remove other users' reviews of them |
| Library admin | also grant and revoke roles in the library (Administration → Library members) |
| Global admin | everything, in every library: users, server settings, system-wide API keys |

A user's role in a library is the highest of: one granted in the app, one mapped from a group in the identity
provider (`raggedlib.auth.group-roles`), and the default for everyone (`raggedlib.auth.default-library-role`, `none`
unless set). Global admins are the users whose groups claim holds one of `raggedlib.auth.admin-values`
(`AUTH_ADMIN_GROUPS` in docker-compose), plus those another admin promoted in the app. Without groups, match on
another claim: `RAGGEDLIB_AUTH_ADMIN_CLAIM=email RAGGEDLIB_AUTH_ADMIN_VALUES=me@example.com`. Admins can also disable
users: their sessions and API keys stop working at once.

Every user keeps **favorite lists** of books (private, or shared read-only with every signed-in user) and can write a
**review** of any book they can see: a 1–5 rating, a text, or both. Lists and reviews are on the Lists tab and in a
book's details. **Save books to a list…** (on Search results, in Ask's toolbar, and as the bookmark in the Sources
panel) puts the books behind a search, a conversation or one answer on a new list or an existing one, all of them or
the ones picked (in Ask, just the cited ones with a click).

**The bundled dex** (`infra/dex/config.yaml`, adapted from onto-workbench) has seven users; the password is the
part before the `@` (or the whole name). Their groups map to roles in the `dex` profile (`application-dex.yml`):

| User | Groups | Role |
|---|---|---|
| `gadmin` / `gadmin` | `administrators` | global admin |
| `gcurator` / `gcurator` | `curators` | curator of every library |
| `admin@raggedlib.dev` | `administrators` | global admin |
| `librarian@raggedlib.dev` | `librarians` | library admin of every library |
| `curator@raggedlib.dev` | `curators` | curator of every library |
| `reader@raggedlib.dev` | `readers` | viewer of every library |
| `user@raggedlib.dev` | none | nothing, until someone grants a role |

`gadmin` and `gcurator` sign in with just the name, in the field dex labels "email address".

Its issuer is `http://dex.localhost:5556/dex`: browsers resolve `*.localhost` to the loopback address, and inside the
compose network `dex.localhost` is the dex container's alias, so the browser and the backend (in its container or on
the host) reach dex at the same URL, as OIDC requires.

**Your own provider** (Keycloak, Authentik, Entra ID, …): register a confidential client with the redirect URI
`http://localhost:3000/api/auth/callback/oidc` (your web UI's origin), then in `.env`:

```bash
SPRING_PROFILES_ACTIVE=oidc
OIDC_ISSUER_URI=https://idp.example.com/realms/home
OIDC_CLIENT_ID=raggedlib
OIDC_CLIENT_SECRET=...
OIDC_SCOPES=openid,profile,email,groups    # whatever puts the groups claim in the ID token
AUTH_ADMIN_GROUPS=raggedlib-admins
```

and map groups to roles with `RAGGEDLIB_AUTH_GROUP_ROLES_0_GROUP=readers RAGGEDLIB_AUTH_GROUP_ROLES_0_ROLE=viewer
RAGGEDLIB_AUTH_GROUP_ROLES_0_LIBRARY=*` (a library name or id instead of `*` for one library), and so on at index 1, 2,
… in the backend's environment. The backend discovers the provider at startup, so it must be reachable then.

**Single-user mode** is a switch, `raggedlib.auth.mode`: `oidc` (users sign in) or `single-user` (no sign-in: every
request without an API key is a built-in local admin, as before multi-user support). In docker-compose it's
`AUTH_MODE` in `.env`:

```bash
AUTH_MODE=single-user      # in .env; then: docker compose up -d backend
```

`single-user` wins over everything else: a configured provider (such as the `dex` profile the compose file activates)
is ignored and not even contacted, so dex can be stopped (`docker compose stop dex`). Everything else still works as
the local user: favorite lists, reviews, API keys (personal and system-wide, with their scopes), and MCP, which still
takes API keys only. The backend logs a warning at startup; keep its ports on localhost. The mode defaults to
`single-user` without a profile (`mvn spring-boot:run`) and to `oidc` with the `oidc` or `dex` profile; with
`oidc` and no provider configured, the backend refuses to start rather than fall back to letting everyone in.

## Use it from agents (MCP)

The backend serves an [MCP](https://modelcontextprotocol.io) endpoint (streamable HTTP, stateless) at
`http://localhost:3000/mcp`, through the web UI's server, or directly at `http://localhost:8090/mcp`. Six read-only
tools search the libraries; with a personal key, more tools reach the key owner's favorite lists and reviews.

| Tool | What it does |
|---|---|
| `search_library` | Reranked passage search (about 1 s, no LLM). Takes `query`, and optionally `topK`, `bookIds`, `tags`, `publishers`, `libraryIds`, `rerank` and `correctSpelling`. An empty result means the books don't cover it. |
| `ask_library` | A cited answer from the local chat model (20–90 s; cut off at 150 s), with its sources as short excerpts. Takes `question`, and optionally `topK`, `bookIds`, `tags`, `publishers`, `libraryIds`, `model` and `correctSpelling`. |
| `list_books` | Find books by title/author substring, tag, publisher, status or library, to get ids for `bookIds` |
| `list_tags` | Calibre tags by number of books |
| `list_libraries` | The configured libraries, with how many books each has, to get ids for `libraryIds` |
| `library_status` | How many books are indexed and searchable |
| `list_favorite_lists`, `get_favorite_list` | The user's favorite lists and the ones others share, and a list's books (`favorites:read`) |
| `create_favorite_list`, `add_to_favorite_list`, `add_books_to_favorite_list`, `remove_from_favorite_list` | Keep the user's lists, adding one book or several at once (`favorites:write`) |
| `get_book_reviews`, `list_my_reviews` | A book's reviews with the average rating; the user's own reviews (`reviews:read`) |
| `write_book_review` | The user's review of a book: a rating 1–5 and/or a text (`reviews:write`) |

Every MCP request needs an API key, sent as `Authorization: Bearer <key>` or `X-API-Key: <key>`:

- **Personal keys** (as in onto-workbench): each user mints their own in the web UI, under their name in the navbar →
  *API keys*, choosing the scopes (`library:read`, `library:curate`, `favorites:read|write`, `reviews:read|write`),
  optionally a subset of their libraries, and an expiry. The key is shown once; only its hash is stored. It acts as
  the user, never with more than the user's own roles, and `tools/list` shows only the tools its scopes allow.
  Revoking it takes effect at once.
- **System-wide keys**: admins create them under *Administration → System API keys*, for clients that aren't any one
  user (a team chatbot, a CI job). They have library scopes only, optionally for some libraries, and never see the
  per-user tools.
- **Configured keys**: `raggedlib.mcp.api-keys` (`MCP_API_KEY` in docker-compose) still works, as a system-wide key
  with `library:read` on every library.

```bash
claude mcp add --transport http raggedlib http://localhost:3000/mcp --header "Authorization: Bearer $RAGGEDLIB_MCP_KEY"
```

The same keys work on the REST API (`/api`), within their scopes; managing keys, users and roles needs a browser
session.

`ask_library` runs for up to 150 s, and the stateless transport sends no progress updates meanwhile. Give it a tool timeout of at least 180 s: many clients default to 60 s. In Claude Code, set `MCP_TOOL_TIMEOUT=180000`.

Other harnesses take the same URL and header, for example as JSON: `{"type": "http", "url": "http://localhost:3000/mcp", "headers": {"Authorization": "Bearer <key>"}}`.

**Agent skill.** [`skills/raggedlib/`](skills/raggedlib/SKILL.md) is an [Agent Skill](https://agentskills.io) that tells an agent when to reach for these tools and how to use them well: `search_library` first, retrying with other words before concluding the books don't cover something, narrowing to books and tags, reading scores, and citing pages or sections. It also has setup notes for Claude Code, Codex and other harnesses, and `scripts/raggedlib_mcp.py`, which calls the tools over plain HTTP where no MCP client is connected (`RAGGEDLIB_MCP_KEY` holds the key). Install it by linking or copying the folder into the harness's skills directory (`~/.claude/skills/`, `~/.agents/skills/`, …), or with `npx skills add larsw/raggedlib`.

Configured keys are a list of `{name, key}` in `raggedlib.mcp.api-keys`. The name only identifies the client in logs. A key can be stored as `sha256:<hex>` instead of in plain text; `printf %s "$KEY" | sha256sum` gives the hex. Clients still send the plain key. In docker-compose, `MCP_API_KEY` (and `MCP_API_KEY_NAME`) fill entry 0. For more keys, add `RAGGEDLIB_MCP_API_KEYS_1_NAME` / `RAGGEDLIB_MCP_API_KEYS_1_KEY` and so on to the backend's environment.

## How it works

- **Sync** (`POST /api/library/sync`): reads metadata with `calibredb list`. If calibredb refuses, it falls back to reading `metadata.db` read-only. That happens when the Calibre GUI is open, and also inside the container, where the mount is read-only. Books whose calibre `last_modified` changed are queued for re-indexing.
- **Extraction**: one format is chosen per book, in the order EPUB > AZW3 > MOBI > FB2 > PDF > LIT > CHM > DJVU. PDFs go through `pdftotext`, which keeps page numbers; pages without a text layer go through OCR (next point). Everything else goes through `ebook-convert` to markdown, which gives section headings. The text is then cleaned up: headers and footers, TOC and index pages, and hyphenation. It is split into chunks of about 1,500 characters that overlap and respect paragraph boundaries. PDFs carry no heading markup, so their sections come from heading-like lines; table-of-contents pages near the front (many entries, dot leaders, "Chapter N" mentions) are recognised and contribute none, so a book's last TOC entry no longer labels its whole front matter (`Structure.kt`).
- **OCR**: a PDF without a text layer (under 80 letters per page) is read with Tesseract. Each page short of text is rendered at 300 dpi with `pdftoppm` and recognised in the book's Calibre languages plus `OCR_LANGUAGES`, and its text takes the page's place, so cleanup, sections and page citations work as for any PDF. In a PDF that has a text layer, only textless pages that are mostly a raster image (scanned pages bound into it) are OCR'd, and kept only if they hold a page of text, not a library stamp or part title. Pages read with a mean word confidence under 50 are dropped: photos and diagrams, and contents pages, whose dot leaders read as noise. Pages from all books share 8 single-threaded Tesseract runs (half the cores, at most 8; `raggedlib.ocr.concurrency`): a 118-page scan at 600 dpi takes about 45 s, and the Indexing tab shows the page count as it goes. The book details say how many pages came from OCR. PDFs skipped as "no text layer" before OCR was available are queued again at startup (`PdfOcr.kt`, `OcrText.kt`). On the two scanned PDFs here (an English book, a Norwegian court ruling) mean word confidence is 94 and the text needs no correcting, though a decorative chapter numeral can come out as a letter of another configured language ("CHAPTER å").
- **Duplicates**: a Calibre library can hold one book twice, under the same or another title. Before a book is embedded, its passages are looked up (by md5) in the other indexed books; if 90% or more are, word for word, in one at least as long, it is marked DUPLICATE of that book and gets no passages of its own (`Duplicates.kt`). The check also runs the other way after each book is indexed, so it doesn't matter which copy comes first. Searches restricted to the duplicate, or to a tag only it has, use the kept copy; if that copy leaves the library, the duplicate is indexed again. Repeated passages that aren't whole-book copies (an introduction printed twice in a PDF, front matter shared by volumes) are collapsed in search results when they share 60% of their word 5-grams (`NearDuplicates.kt`).
- **Re-indexing** keeps a book's passages searchable until the new ones replace them (a re-index that fails keeps them too), and reuses the embedding of every passage whose title, section and text are unchanged and that came from the current embedding model (`raggedlib.embedding.legacy-model` names the model of passages stored before that was recorded). `POST /api/ingest/reprocess` re-extracts already indexed books this way, e.g. after an extraction fix.
- **Indexing**: runs in the background and resumes after a restart. 3 extraction workers feed one GPU embedder, which sends batches of 32 to Ollama. Each book is written in one transaction. Expect about 25 chunks/s on the RTX 3500 Ada, which is about 5–6 hours for the whole library. Progress, ETA and errors are on the Indexing tab.
- **Retrieval**: pgvector cosine search (HNSW) plus Postgres full-text search, fused with weighted reciprocal rank fusion. Each vector hit counts in proportion to its cosine similarity, from 0 at 0.40 to full at 0.70. That keeps a weak vector side, where the embedding misreads a query like "return oriented programming gadgets" as being about OOP, from outvoting strong keyword matches. Full-text search first requires every term, honoring websearch syntax: quotes, `-term`, `or`. If that finds too little, a fallback looks for passages holding enough of the query's *distinctive* terms, ranked by IDF-weighted term coverage. In a programming library "program" and "return" say little and "gadget" says a lot, so matching only the common words doesn't count (`KeywordFallback.kt`). The best 30 fused passages (or `topK`, if larger) are then re-scored by a cross-encoder, bge-reranker-v2-m3 served by llama.cpp, which reads query and passage together. That improves the order and gives an absolute relevance score: passages below 0.10 are dropped, so a query nothing in the library matches ("who won the 1998 world cup") comes back empty instead of with the least-bad passages. It costs about 1 s per search, can be switched off per request (the sort button next to the scope controls), and if the reranker is down, saturated (all 4 slots busy) or slow, search falls back to the fused order; after a failure it is skipped for 15 s rather than every search waiting out the timeout. Results are capped at 3 passages per book for variety, and can be limited to particular books, tags or publishers.
- **Spelling correction**: before searching, misspelled query words are replaced from the library's own vocabulary (about 190k words that occur in 3+ passages, built with `ts_stat`). A word that occurs nowhere in the library is corrected to the closest word in 20+ passages: one edit away (a missing, extra, wrong or swapped letter), or two for words of 9+ letters. A word the library does contain, rarely, is corrected only to a one-edit neighbour 200× as frequent, which catches the classic typos books contain too ("recieve", 24 passages, vs "receive", 11,000) and leaves real words alone ("minced" stays, though "minded" is far more common). Corrections keep the first letter (or swap the first two), where typos are rare and names differ ("Niri" isn't "Siri"). Words under 5 letters, camelCase or ALL-CAPS words, and anything glued to digits or `_ . - / : @ #` (identifiers, flags, file names, `-exclusions`) are left alone. The corrected query is used for every stage (keyword search, embedding, reranker). The Search tab says "Showing results for …" with a link to search for the words as typed, the Ask tab shows "searched for …" with "ask as typed", and the API returns `correctedQuery` (`correct: false` turns it off). Unknown names close to a frequent word can still be "corrected", which is what those links are for. The vocabulary is rebuilt in the background (about 3 min) at startup when missing or stale, after each indexing run, and after a sync removed books (`Spelling.kt`, `SpellingCorrector.kt`, `SpellingVocabulary.kt`).
- **Chat**: a follow-up question is first rewritten into a standalone query. The answer streams over SSE with `[n]` citations. API contract: [`docs/API.md`](docs/API.md).
- **Reading**: ebooks open in a reader drawer over the current tab, built on [foliate-js](https://github.com/johnfactotum/foliate-js) (EPUB, KEPUB, AZW3, AZW, MOBI, PRC, FB2, FBZ, CBZ). PDFs open in the browser's own viewer at the cited page. Ebook passages store no position in the book, only their text and section heading, so **Read** on a source card finds the passage again: the TOC entry matching its heading narrows where to look, then runs of 8 words from its start and end are matched against the book's text with case, accents, punctuation and whitespace folded, and the span between them is highlighted (`locate.ts`). On the two indexed EPUBs it finds every passage, and the whole span for 98%; the rest (code-heavy passages, ones crossing a file boundary) are highlighted from their first or last words. The book details panel's **Read**, its ebook files and its indexed sections open the reader too; the last position per book is remembered. foliate-js is served unbundled from `node_modules` (`/vendor/foliate-js/`), so only the modules a book needs load, and only when the reader opens.

## Layout

```
backend/    Spring Boot app (dev.raggedlib.{calibre,extract,ingest,search,chat,library,system,auth,favorites,reviews,mcp})
frontend/   Bun server (server.ts: static, /api and /mcp proxy, /vendor/foliate-js) and React app (src/{api,views,components,hooks,lib})
infra/dex/  development OIDC provider (docker-compose, AuthIntegrationTest)
docs/API.md HTTP contract
skills/     Agent Skill for the MCP tools (skills/raggedlib/SKILL.md)
```

## Troubleshooting

- **Chat is slow, or `ollama ps` shows `100% CPU`.** The Ollama and reranker containers get the GPU through CDI
  (a `driver: cdi` device reservation for `nvidia.com/gpu=all`), so they can't silently lose it after a host
  `systemctl daemon-reload`, which used to show up as "Failed to initialize NVML: Unknown Error". If CDI isn't set up
  on your host, run `sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml` once.
- **`compose up` fails with `error gathering device information … "nvidia.com/gpu=all": no such file or directory`.**
  Docker older than 28.2 ships with CDI switched off. Run
  `sudo nvidia-ctk runtime configure --runtime=docker --cdi.enabled && sudo systemctl restart docker`.
- **Reranker unreachable** (yellow status dot, "Reranker UNREACHABLE" in its tooltip). Search still works in the fused
  order. Check `docker compose logs reranker`; on first start it downloads the model (~640 MB) before it turns healthy.
- **Slow generation even on the GPU.** On a laptop, check `powerprofilesctl get`. The `power-saver` profile keeps the
  GPU in low-clock power states.

## Known limitations

- OCR covers PDFs only: a scanned DJVU is still SKIPPED, as `ebook-convert` reads only an existing text layer. OCR runs on the CPU; Tesseract misreads handwriting, unusual fonts and poor scans, and a scanned page's footnotes and columns follow Tesseract's reading order.
- Code blocks in PDFs come out as single lines.
- Editing a book's title in Calibre re-embeds the whole book (the title is part of every passage's embedding input). Other metadata edits re-extract it but keep the embeddings of unchanged passages.
- PDF sections come from a heuristic ("Chapter 3 …", "3.2 …" lines), so PDFs whose chapters are laid out differently get no section labels.
- The reader can't open LIT, CHM or DJVU books; the drawer offers their files to download instead. A passage indexed from one of those formats is looked up in another format of the book, where its text can differ more.
- Spelling correction learns its vocabulary from every library, so a correction can be a word that only occurs in a library the user can't see (the passages found are always from libraries they can).
- Indexing is one queue for every library: a curator starts it for the books of their own libraries, but any curator can stop it.
- Sessions live in the backend's memory: restarting it signs everyone out (API keys keep working). dex, as configured for development, keeps nothing either.
