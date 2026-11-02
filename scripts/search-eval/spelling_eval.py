#!/usr/bin/env python3
"""Spelling correction against the running backend (POST /api/search, `correctedQuery`).

1. misspelled queries (queries.TYPOS) must be corrected as expected;
2. correctly spelled queries (queries.QUERIES, queries.MUST_NOT_CHANGE) must come back unchanged;
3. --rare N: N random words that occur in exactly 3 passages (from the spelling vocabulary) — rare but real
   library words; corrections there are mostly typos in the books, but should stay few.

usage: spelling_eval.py [--rare 150] [--verbose]
"""
import argparse
import os
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import psql, search  # noqa: E402
from queries import MUST_NOT_CHANGE, QUERIES, TYPOS  # noqa: E402


def corrected(q: str):
    r = search(q, topK=1, rerank=False)
    return r.get("correctedQuery"), r.get("corrections") or []


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rare", type=int, default=0, help="also check N rare library words")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    failures = 0
    with ThreadPoolExecutor(4) as ex:
        typo = list(ex.map(lambda t: (t, corrected(t[0])[0]), TYPOS))
        clean_q = [q for q, _ in QUERIES] + MUST_NOT_CHANGE
        clean = list(ex.map(lambda q: (q, corrected(q)[0]), clean_q))

    ok = 0
    for (typed, want), got in typo:
        hit = got == want if want else got is not None
        ok += hit
        if not hit or a.verbose:
            print(f"  {'ok  ' if hit else 'MISS'} {typed!r} -> {got!r}" + ("" if hit else f" (expected {want!r})"))
    print(f"misspelled queries corrected as expected: {ok}/{len(typo)}")
    failures += len(typo) - ok

    changed = [(q, g) for q, g in clean if g]
    for q, g in changed:
        print(f"  CHANGED {q!r} -> {g!r}")
    print(f"correct queries left alone: {len(clean) - len(changed)}/{len(clean)}")
    failures += len(changed)

    if a.rare:
        words = psql(f"select word from search_vocabulary where ndoc = 3 and length(word) >= 5 order by random() limit {a.rare}").split()
        with ThreadPoolExecutor(4) as ex:
            rare = list(ex.map(lambda w: (w, corrected(w)[1]), words))
        hits = [(w, c) for w, c in rare if c]
        for w, c in hits:
            print(f"  rare {w!r}: " + ", ".join(f"{x['from']}->{x['to']}" for x in c))
        print(f"rare library words changed: {len(hits)}/{len(words)} (mostly typos in the books themselves)")

    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
