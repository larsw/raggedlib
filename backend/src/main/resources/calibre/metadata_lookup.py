import base64
import json
import queue
import re
import sys
import threading
import time
from datetime import date, datetime
from html.parser import HTMLParser

from calibre.ebooks.metadata.sources.identify import identify, metadata_plugins
from calibre.utils.logging import Log

COVER_IDENTIFY_TIMEOUT = 10
COVER_PHASE_TIMEOUT = 18
MAX_COVER_BYTES = 10 * 1024 * 1024


class PlainText(HTMLParser):
    BLOCKS = {"address", "article", "blockquote", "br", "div", "li", "p", "section"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.ignored = 0

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style"}:
            self.ignored += 1
        elif not self.ignored and tag in self.BLOCKS:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in {"script", "style"} and self.ignored:
            self.ignored -= 1
        elif not self.ignored and tag in self.BLOCKS:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.ignored:
            self.parts.append(data)


def plain_text(value):
    parser = PlainText()
    parser.feed(value or "")
    text = "".join(parser.parts).replace("\r", "")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    return text[:500].rstrip()


def as_list(value):
    if value is None:
        return []
    if isinstance(value, str):
        return [value] if value else []
    return [str(item) for item in value if item]


def published_date(value):
    if isinstance(value, datetime):
        return value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    return None


def requested_metadata(request, log, abort, timeout):
    return identify(
        log,
        abort,
        title=request.get("title"),
        authors=request.get("authors") or None,
        identifiers=request.get("identifiers") or {},
        timeout=timeout,
    )


def cover_candidates(request):
    log = Log()
    abort = threading.Event()
    plugins = list(metadata_plugins({"cover"}))
    if not plugins:
        return {"candidates": [], "timedOut": False, "failedSources": []}

    title = request.get("title")
    authors = request.get("authors") or None
    identifiers = request.get("identifiers") or {}
    requested_metadata(request, log, abort, COVER_IDENTIFY_TIMEOUT)

    results = queue.Queue()
    failures = queue.Queue()

    def download(plugin):
        try:
            plugin.download_cover(
                log,
                results,
                abort,
                title=title,
                authors=authors,
                identifiers=identifiers,
                timeout=COVER_PHASE_TIMEOUT,
                get_best_cover=False,
            )
        except Exception:
            log.exception("Cover download failed for %s", getattr(plugin, "name", "Unknown"))
            failures.put(str(getattr(plugin, "name", "Unknown")))

    threads = [
        threading.Thread(target=download, args=(plugin,), daemon=True)
        for plugin in plugins
    ]
    for thread in threads:
        thread.start()

    deadline = time.monotonic() + COVER_PHASE_TIMEOUT
    for thread in threads:
        thread.join(max(0, deadline - time.monotonic()))
    timed_out = any(thread.is_alive() for thread in threads)
    if timed_out:
        abort.set()
        for thread in threads:
            thread.join(0.1)

    candidates = []
    while True:
        try:
            plugin, cover_data = results.get_nowait()
        except queue.Empty:
            break
        image = bytes(cover_data)
        source = str(getattr(plugin, "name", "Unknown"))
        if not image or len(image) > MAX_COVER_BYTES:
            failures.put(source)
            continue
        candidates.append({"source": source, "image": base64.b64encode(image).decode("ascii")})

    failed_sources = set()
    while True:
        try:
            failed_sources.add(failures.get_nowait())
        except queue.Empty:
            break
    return {
        "candidates": candidates,
        "timedOut": timed_out,
        "failedSources": sorted(failed_sources),
    }


with open(sys.argv[1], encoding="utf-8") as request_file:
    request = json.load(request_file)

if request.get("mode") == "covers":
    result = cover_candidates(request)
else:
    results = requested_metadata(request, lambda *args: None, threading.Event(), 30)
    candidates = []
    for result in results:
        language = getattr(result, "languages", None) or getattr(result, "language", None)
        plugin = getattr(result, "identify_plugin", None)
        source = getattr(plugin, "name", None) or getattr(result, "source", None) or "Unknown"
        identifiers = getattr(result, "identifiers", None) or {}
        candidates.append(
            {
                "source": str(source),
                "title": getattr(result, "title", None) or "",
                "authors": as_list(getattr(result, "authors", None)),
                "publisher": getattr(result, "publisher", None) or None,
                "publishedDate": published_date(getattr(result, "pubdate", None)),
                "identifiers": {str(key): str(value) for key, value in identifiers.items() if key and value},
                "languages": as_list(language),
                "tags": as_list(getattr(result, "tags", None)),
                "description": plain_text(getattr(result, "comments", None)) or None,
            }
        )
    result = candidates

print(json.dumps(result, ensure_ascii=False))
