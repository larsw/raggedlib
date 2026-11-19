import json
import runpy
import sys
import struct
import zlib
from datetime import datetime, timezone
from types import SimpleNamespace

import calibre.ebooks.metadata.sources.identify as identify_module


with open(sys.argv[2], encoding="utf-8") as request_file:
    request = json.load(request_file)


def fake_identify(log, abort, **kwargs):
    expected = {
        "title": "The Example",
        "authors": ["A. Writer"],
        "identifiers": {"isbn": "9780000000001"},
        "timeout": 10 if request.get("mode") == "covers" else 30,
    }
    assert kwargs == expected
    return [
        SimpleNamespace(
            identify_plugin=SimpleNamespace(name="Stub Source"),
            title="The Example",
            authors=["A. Writer"],
            publisher="Example Press",
            pubdate=datetime(2020, 5, 6, tzinfo=timezone.utc),
            identifiers={"isbn": "9780000000002", "doi": "10.0000/example"},
            language="eng",
            tags=["Fiction"],
            comments="<p>A <i>plain</i> description.</p><script>hidden</script><p>Second paragraph.</p>",
        ),
        SimpleNamespace(
            identify_plugin=SimpleNamespace(name="Another Stub"),
            title="The Example, Revised",
            authors=["A. Writer"],
            publisher=None,
            pubdate=None,
            identifiers={},
            language=None,
            tags=[],
            comments=None,
        ),
    ]


def png_chunk(kind, data):
    payload = kind + data
    return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)


def png_pixel(color):
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(b"\x00" + bytes(color)))
        + png_chunk(b"IEND", b"")
    )


class StubCoverSource:
    def __init__(self, name, image):
        self.name = name
        self.image = image

    def download_cover(self, log, result_queue, abort, title, authors, identifiers, timeout, get_best_cover):
        assert title == "The Example"
        assert authors == ["A. Writer"]
        assert identifiers == {"isbn": "9780000000001"}
        assert timeout == 18
        assert get_best_cover is False
        result_queue.put((self, self.image))


cover_sources = [
    StubCoverSource("Stub Cover Source A", png_pixel((255, 0, 0))),
    StubCoverSource("Stub Cover Source B", png_pixel((0, 0, 255))),
]


def fake_metadata_plugins(capabilities):
    assert capabilities == {"cover"}
    return cover_sources


identify_module.identify = fake_identify
identify_module.metadata_plugins = fake_metadata_plugins
script_path, request_path = sys.argv[1:]
sys.argv = [script_path, request_path]
runpy.run_path(script_path, run_name="__main__")
