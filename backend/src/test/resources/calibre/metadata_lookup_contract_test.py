import runpy
import sys
from datetime import datetime, timezone
from types import SimpleNamespace

import calibre.ebooks.metadata.sources.identify as identify_module


def fake_identify(log, abort, **kwargs):
    assert kwargs == {
        "title": "The Example",
        "authors": ["A. Writer"],
        "identifiers": {"isbn": "9780000000001"},
        "timeout": 30,
    }
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


identify_module.identify = fake_identify
script_path, request_path = sys.argv[1:]
sys.argv = [script_path, request_path]
runpy.run_path(script_path, run_name="__main__")
