# Search evals

Checks of the running stack's retrieval, through its API (`RAGGEDLIB_API`, default `http://localhost:8080/api`).
Python 3 standard library only; run from anywhere.

| Script | What it checks |
|---|---|
| `search_eval.py` | The 36 queries in `queries.py` (concepts and exact identifiers across the library's subjects). Book-level relevance via a title regex per query: P@k (precision over the results returned, which the reranker's cutoff can make fewer than k; empty results are counted separately), MRR, bad@3 (irrelevant books in the top 3), empty results, latency. `--compare` runs reranking on vs off; `--judge` has a local LLM (Ollama, `OLLAMA_URL`) grade every passage 0–2 and adds nDCG@k (grades cached in `.judge-cache.json`). `--show` lists the results. |
| `spelling_eval.py` | Spelling correction: misspelled queries must be corrected as expected, correctly spelled ones (including names and rare real words) must come back unchanged; `--rare N` also tries N words that occur in exactly 3 passages. Exits non-zero on a miss. |
| `label_survey.sh [N]` | Section labels of N random library PDFs (default 60) under the current heuristics vs the earlier ones: front matter labelled with a later chapter, real labels lost, page-numbered running heads read as headings. Needs `pdftotext` and the Calibre library on this host (`RAGGEDLIB_LIBRARY`). |

```sh
scripts/search-eval/search_eval.py --compare
scripts/search-eval/search_eval.py --judge          # slow the first time: ~300 LLM calls
scripts/search-eval/spelling_eval.py --rare 150
scripts/search-eval/label_survey.sh 60
```

Book-level relevance is approximate: a relevant book can return an off-topic passage, and an unlisted book can be
relevant. Use it to compare runs, not as an absolute score; `--judge` grades the passages themselves.
