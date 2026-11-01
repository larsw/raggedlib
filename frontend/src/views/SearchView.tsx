import { Button, Classes, InputGroup, Intent, NonIdealState, Spinner } from "@blueprintjs/core";
import { useMemo, useRef, useState } from "react";
import { api, isAbortError } from "../api/client";
import type { SearchRequest, SourceChunk } from "../api/types";
import { useApp } from "../AppContext";
import { ErrorState } from "../components/ErrorState";
import { ScopeBar } from "../components/scope/ScopeBar";
import { SourceCard } from "../components/SourceCard";
import { queryTerms } from "../lib/highlight";

/** Plain hybrid passage search (no LLM). */
export function SearchView() {
  const { scope, system } = useApp();
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState<string | null>(null);
  /** whether the reranker took part in the search being shown (the toggle may have changed since) */
  const [reranked, setReranked] = useState(false);
  const [results, setResults] = useState<SourceChunk[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>();
  const ctrlRef = useRef<AbortController | null>(null);

  const terms = useMemo(() => (submitted ? queryTerms(submitted) : []), [submitted]);

  const run = async (q = query) => {
    const text = q.trim();
    if (!text) return;
    ctrlRef.current?.abort();
    const ctrl = new AbortController();
    ctrlRef.current = ctrl;
    setLoading(true);
    setError(undefined);
    setSubmitted(text);
    setReranked(scope.rerank && !!system?.rerankEnabled && !!system.rerankerReachable);
    const req: SearchRequest = {
      query: text,
      topK: scope.topK,
      ...(scope.books.length ? { bookIds: scope.books.map((b) => b.id) } : {}),
      ...(scope.tags.length ? { tags: scope.tags } : {}),
      rerank: scope.rerank,
    };
    try {
      const res = await api.search(req, ctrl.signal);
      setResults(res);
    } catch (err) {
      if (!isAbortError(err)) setError(err);
    } finally {
      if (ctrlRef.current === ctrl) setLoading(false);
    }
  };

  const bookCount = results ? new Set(results.map((r) => r.bookId)).size : 0;

  return (
    <div className="search-view">
      <div className="search-controls">
        <form
          className="search-form"
          onSubmit={(e) => {
            e.preventDefault();
            void run();
          }}
        >
          <InputGroup
            size="large"
            leftIcon="search"
            placeholder="Search passages, e.g. heap spraying, CSRF tokens, borrow checker…"
            aria-label="Search query"
            value={query}
            onChange={(e) => setQuery(e.currentTarget.value)}
            rightElement={loading ? <Spinner size={18} className="input-spinner" /> : undefined}
            autoFocus
            fill
          />
          <Button type="submit" size="large" intent={Intent.PRIMARY} text="Search" disabled={!query.trim()} loading={loading} />
        </form>
        <ScopeBar showModel={false} />
      </div>

      <div className="search-results">
        {error ? (
          <ErrorState error={error} onRetry={() => void run(submitted ?? query)} title="Search failed" />
        ) : results === null ? (
          loading ? (
            <NonIdealState icon={<Spinner />} description="Searching…" />
          ) : (
            <NonIdealState
              icon="search"
              title="Hybrid passage search"
              description="Vector + full-text search over every indexed passage, re-scored by a small cross-encoder — no chat model involved. Fast for finding where a topic is covered."
            />
          )
        ) : results.length === 0 ? (
          <NonIdealState
            icon="inbox-search"
            title="No passages found"
            description={
              reranked
                ? "Nothing in the library looked relevant enough to the reranker. Try other terms, widen the scope, or turn reranking off."
                : "Try other terms or widen the scope."
            }
          />
        ) : (
          <>
            <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} results-summary`}>
              {results.length} passages from {bookCount} book{bookCount === 1 ? "" : "s"} for “{submitted}”
            </div>
            <div className={`results-list${loading ? " is-stale" : ""}`}>
              {results.map((r) => (
                <SourceCard key={r.chunkId || r.ref} source={r} terms={terms} showScore />
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
