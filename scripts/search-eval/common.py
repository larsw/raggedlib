"""HTTP helpers shared by the eval scripts (standard library only)."""
import json
import os
import subprocess
import urllib.request

API = os.environ.get("RAGGEDLIB_API", "http://localhost:8080/api").rstrip("/")
OLLAMA = os.environ.get("OLLAMA_URL", "http://localhost:11434").rstrip("/")
REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def post(url: str, body: dict, timeout: float = 120):
    req = urllib.request.Request(url, json.dumps(body).encode(), {"content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def search(query: str, **opts) -> dict:
    """POST /api/search; opts: topK, rerank, correct, bookIds, tags."""
    return post(f"{API}/search", {"query": query, **opts})


def psql(sql: str) -> str:
    """Runs SQL in the compose stack's Postgres (read-only use only), returns tab-free unaligned rows."""
    r = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "raggedlib", "-d", "raggedlib", "-tAq",
         "-v", "ON_ERROR_STOP=1"],
        input=sql, capture_output=True, text=True, cwd=REPO,
    )
    if r.returncode != 0:
        raise RuntimeError(r.stderr[:2000])
    return r.stdout.strip()
