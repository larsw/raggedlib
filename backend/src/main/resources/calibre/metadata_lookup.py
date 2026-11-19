import json
import re
import sys
import threading
from datetime import date, datetime
from html.parser import HTMLParser

from calibre.ebooks.metadata.sources.identify import identify


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


with open(sys.argv[1], encoding="utf-8") as request_file:
    request = json.load(request_file)

results = identify(
    lambda *args: None,
    threading.Event(),
    title=request.get("title"),
    authors=request.get("authors") or None,
    identifiers=request.get("identifiers") or {},
    timeout=30,
)

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

print(json.dumps(candidates, ensure_ascii=False))
