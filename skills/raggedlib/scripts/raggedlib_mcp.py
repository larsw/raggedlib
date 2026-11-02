#!/usr/bin/env python3
"""Call raggedlib's MCP tools over plain HTTP, for harnesses or shells without the MCP server connected.

The server speaks stateless streamable HTTP: each JSON-RPC request is one POST, no session needed.

    raggedlib_mcp.py [--brief | --refs N,M] <tool> [key=value ...]   arguments as key=value
    raggedlib_mcp.py [--brief | --refs N,M] <tool> '<json object>'   arguments as one JSON object
    raggedlib_mcp.py --list                                          tool names and descriptions

key=value values are parsed as JSON when they can be (topK=5, rerank=false, 'bookIds=[6,12]') and taken as text
otherwise. Quote anything with spaces or brackets for the shell: query="event sourcing", 'bookIds=[6,12]' (zsh
treats unquoted [..] as a glob).

--brief prints search_library and ask_library passages as one header line plus the first 200 characters each,
and list_books as one line per book, instead of the full JSON: for scanning results before reading in full.
--refs 1,3 prints only those passages (by ref), each with its full text: rerun the same search with it to read
the promising ones from a --brief scan. Searches are deterministic, so the refs stay the same.

Environment:
    RAGGEDLIB_MCP_KEY   API key (required); sent as "Authorization: Bearer <key>", never printed
    RAGGEDLIB_MCP_URL   endpoint, default http://localhost:8080/mcp
    RAGGEDLIB_MCP_TIMEOUT  seconds, default 200 (ask_library can take up to ~150 s)

Prints the tool's result (JSON, pretty-printed). Exit status 1 on a tool or connection error, 2 on bad usage.
"""
import json
import os
import sys
import urllib.error
import urllib.request

URL = os.environ.get("RAGGEDLIB_MCP_URL", "http://localhost:8080/mcp")
TIMEOUT = float(os.environ.get("RAGGEDLIB_MCP_TIMEOUT", "200"))


def fail(message, status=1):
    print(f"raggedlib_mcp: {message}", file=sys.stderr)
    sys.exit(status)


def parse_args(items):
    if len(items) == 1 and items[0].lstrip().startswith("{"):
        try:
            return json.loads(items[0])
        except json.JSONDecodeError as e:
            fail(f"arguments are not valid JSON: {e}", 2)
    args = {}
    for item in items:
        key, sep, value = item.partition("=")
        if not sep or not key:
            fail(f"expected key=value, got {item!r}", 2)
        try:
            args[key] = json.loads(value)  # numbers, booleans, [lists]
        except json.JSONDecodeError:
            args[key] = value  # plain text
    return args


def rpc(method, params):
    key = os.environ.get("RAGGEDLIB_MCP_KEY", "").strip()
    if not key:
        fail("set RAGGEDLIB_MCP_KEY to the server's MCP API key (in the raggedlib repo: MCP_API_KEY in .env)", 2)
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(URL, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {key}",
    })
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            raw = resp.read().decode("utf-8")
            kind = resp.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        if e.code == 401:
            fail(f"{URL} refused the API key (401): wrong key, or none configured on the server")
        fail(f"{URL} answered HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}")
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        fail(f"can't reach {URL}: {getattr(e, 'reason', e)} (is the raggedlib backend running?)")
    if "text/event-stream" in kind:  # the last data: line holds the response
        lines = [l[5:].strip() for l in raw.splitlines() if l.startswith("data:")]
        raw = lines[-1] if lines else ""
    try:
        message = json.loads(raw)
    except json.JSONDecodeError:
        fail(f"unexpected response: {raw[:300]}")
    if "error" in message:
        fail(f"{message['error'].get('message', message['error'])}")
    return message["result"]


def where(p):
    if p.get("page") is not None:
        return f"p. {p['page']}"
    return f"§ {p['section']}" if p.get("section") else "-"


def brief(data):
    """One line (plus a text preview) per passage or book; None when there's no short form for [data]."""
    if not isinstance(data, dict):
        return None
    lines = []
    if "passages" in data or "sources" in data:
        for key in ("correctedQuery", "searchQuery", "note"):
            if data.get(key):
                lines.append(f"{key}: {data[key]}")
        if "answer" in data:
            lines += ["answer:", data["answer"], "", "sources:"]
        for p in data.get("passages") or data.get("sources") or []:
            text = " ".join(p.get("text", "").split())
            lines.append(f"[{p['ref']}] {p['score']:.2f} {p['scoreKind']}  book {p['bookId']} {p['title']}, {where(p)}")
            lines.append(f"    {text[:200]}{'…' if len(text) > 200 else ''}")
        if not (data.get("passages") or data.get("sources")):
            lines.append("(no passages)")
        return "\n".join(lines)
    if "books" in data:
        lines.append(f"{data['total']} books (page {data['page']}, size {data['size']})")
        for b in data["books"]:
            dup = f" duplicateOf={b['duplicateOf']}" if b.get("duplicateOf") else ""
            lines.append(f"{b['id']:>6}  {b['status']:<9} {b['chunks']:>5} passages  {b['title']} — "
                         f"{', '.join(b['authors'])}  [{', '.join(b['tags'])}]{dup}")
        return "\n".join(lines)
    return None


def selected(data, refs):
    """Only the passages with these refs, in full; None when [data] has no passages."""
    if not isinstance(data, dict) or not ("passages" in data or "sources" in data):
        return None
    chosen = [p for p in data.get("passages") or data.get("sources") or [] if p.get("ref") in refs]
    if not chosen:
        return f"(none of refs {sorted(refs)} in this result)"
    return "\n\n".join(
        f"[{p['ref']}] {p['score']:.2f} {p['scoreKind']}  book {p['bookId']} {p['title']}, {where(p)}\n{p['text']}"
        for p in chosen
    )


def show(text, short, refs):
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        print(text)
        return
    out = selected(data, refs) if refs else brief(data) if short else None
    print(out or json.dumps(data, indent=2, ensure_ascii=False))


def main(argv):
    short = bool(argv) and argv[0] == "--brief"
    if short:
        argv = argv[1:]
    refs = None
    if argv and argv[0] == "--refs":
        if len(argv) < 2:
            fail("--refs needs a list like 1,3", 2)
        try:
            refs = {int(r) for r in argv[1].split(",") if r.strip()}
        except ValueError:
            fail(f"--refs takes numbers like 1,3, not {argv[1]!r}", 2)
        argv = argv[2:]
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 0 if argv else 2
    if argv[0] == "--list":
        for tool in rpc("tools/list", {})["tools"]:
            print(f"{tool['name']}: {' '.join(tool.get('description', '').split())}\n")
        return 0
    result = rpc("tools/call", {"name": argv[0], "arguments": parse_args(argv[1:])})
    texts = [c.get("text", "") for c in result.get("content", []) if c.get("type") == "text"]
    if result.get("isError"):
        fail(" ".join(texts) or "tool error")
    for text in texts:
        show(text, short, refs)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
