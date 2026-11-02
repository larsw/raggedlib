#!/usr/bin/env python3
"""Search quality against the running backend (POST /api/search).

For each query in queries.QUERIES, the top-k results are scored at book level (does the passage come from a
book whose title matches the query's regex?): P@k (over the results returned: the reranker's cutoff can return
fewer than k), MRR, bad@3 (irrelevant books in the top 3), empty results.
With --judge, a local LLM (Ollama) grades every passage 0/1/2 for the query and nDCG@k is added (the ideal ranking
comes from all passages judged for the query in the compared runs); grades are cached in .judge-cache.json next to
this script, so re-runs only judge new passages.

usage: search_eval.py [--topk 8] [--compare] [--judge] [--model qwen3.5:9b] [--only substr] [--show]
  --compare   run each query with reranking on and off and print both
  --show      print the result titles per query
"""
import argparse
import json
import math
import os
import re
import sys
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import OLLAMA, post, search  # noqa: E402
from queries import QUERIES  # noqa: E402

CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".judge-cache.json")
PROMPT = """You grade search results for a technical book library.

Search query: {q}

Passage (from the book "{title}"{where}):
\"\"\"
{text}
\"\"\"

How useful is this passage for someone who typed that query?
2 = directly about the query's topic: explains, defines or demonstrates it
1 = related: mentions the topic or covers a closely related one, partly useful
0 = not about the query's topic (shares words only, or unrelated)

Answer with a single digit."""


class Judge:
    def __init__(self, model: str):
        self.model = model
        self.lock = threading.Lock()
        try:
            self.cache = json.load(open(CACHE))
        except (OSError, ValueError):
            self.cache = {}

    def grade(self, q: str, r: dict) -> int:
        key = f"{self.model}\x00{q}\x00{r['chunkId']}"
        with self.lock:
            if key in self.cache:
                return self.cache[key]
        where = f", page {r['page']}" if r.get("page") else (f", section \"{r['section']}\"" if r.get("section") else "")
        text = re.sub(r"\s+", " ", r["text"])[:1500]
        out = post(f"{OLLAMA}/api/chat", {
            "model": self.model, "stream": False, "think": False,
            "options": {"temperature": 0, "num_predict": 3, "num_ctx": 4096},
            "messages": [{"role": "user", "content": PROMPT.format(q=q, title=r["title"], where=where, text=text)}],
        }, timeout=300)
        m = re.search(r"[012]", out["message"]["content"])
        g = int(m.group()) if m else 0
        with self.lock:
            self.cache[key] = g
        return g

    def save(self):
        with self.lock:
            tmp = CACHE + ".tmp"
            json.dump(self.cache, open(tmp, "w"))
            os.replace(tmp, CACHE)


def dcg(grades, k):
    return sum((2 ** g - 1) / math.log2(i + 2) for i, g in enumerate(grades[:k]))


def ndcg(grades, pool, k):
    """nDCG@k with the ideal list drawn from [pool]: every passage judged for the query, in all compared runs, so a
    run that finds better passages scores higher, not just one that orders its own well."""
    ideal = dcg(sorted(pool, reverse=True), k)
    return dcg(grades, k) / ideal if ideal else 0.0


def evaluate(queries, topk, rerank, judge, show):
    rows = []
    for q, rx in queries:
        t = time.time()
        res = search(q, topK=topk, rerank=rerank)
        ms = (time.time() - t) * 1000
        results = res["results"]
        rel = [bool(re.search(rx, r["title"], re.I)) for r in results]
        row = {
            "q": q, "n": len(results), "ms": ms,
            "p": sum(rel) / len(rel) if rel else 0.0,
            "mrr": next((1 / (i + 1) for i, x in enumerate(rel) if x), 0.0),
            "bad3": sum(1 for x in rel[:3] if not x),
        }
        if judge:
            with ThreadPoolExecutor(2) as ex:
                grades = list(ex.map(lambda r: judge.grade(q, r), results))
            row["grades"] = grades
            row["graded"] = {r["chunkId"]: g for r, g in zip(results, grades)}
        rows.append(row)
        if show:
            print(f"\n{q}  ({len(results)} results, {ms:.0f} ms)")
            for i, r in enumerate(results):
                g = f" g={row['grades'][i]}" if judge else ""
                print(f"  {'+' if rel[i] else '-'}{g} {r['score']:.3f} {r['title'][:60]} | {(r.get('section') or '')[:40]}")
    return rows


def summary(label, rows, judge):
    n = len(rows)
    avg = lambda key: sum(r[key] for r in rows) / n  # noqa: E731
    line = (f"{label:12} P@k {avg('p'):.3f}  MRR {avg('mrr'):.3f}  bad@3 {sum(r['bad3'] for r in rows):3d}  "
            f"empty {sum(1 for r in rows if r['n'] == 0):2d}  median {sorted(r['ms'] for r in rows)[n // 2]:.0f} ms")
    if judge:
        line += f"  nDCG@k {avg('ndcg'):.3f}  grade-0 in top 3 {sum(g[:3].count(0) for g in (r['grades'] for r in rows))}"
    print(line)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--topk", type=int, default=8)
    ap.add_argument("--compare", action="store_true")
    ap.add_argument("--judge", action="store_true")
    ap.add_argument("--model", default="qwen3.5:9b")
    ap.add_argument("--only")
    ap.add_argument("--show", action="store_true")
    a = ap.parse_args()
    queries = [x for x in QUERIES if not a.only or a.only.lower() in x[0].lower()]
    if not queries:
        sys.exit(f"no query matches --only {a.only!r}")
    judge = Judge(a.model) if a.judge else None
    runs = [("reranked", True), ("fused", False)] if a.compare else [("default", None)]
    results = {}
    try:
        for label, rerank in runs:
            results[label] = evaluate(queries, a.topk, rerank, judge, a.show)
    finally:
        if judge:
            judge.save()
    if judge:
        # ideal per query: all passages judged for it in any run
        for i in range(len(queries)):
            pool = {}
            for rows in results.values():
                pool.update(rows[i]["graded"])
            for rows in results.values():
                rows[i]["ndcg"] = ndcg(rows[i]["grades"], list(pool.values()), a.topk)
    print(f"\n{len(queries)} queries, top {a.topk}:")
    for label, rows in results.items():
        summary(label, rows, judge)
    if not a.show:
        worst = sorted(next(iter(results.values())), key=lambda r: (r["p"], -r["bad3"]))[:5]
        print("\nweakest queries (book-level P@k):")
        for r in worst:
            print(f"  {r['p']:.2f}  bad@3={r['bad3']}  {r['q']}")


if __name__ == "__main__":
    main()
