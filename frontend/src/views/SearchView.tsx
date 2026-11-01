import { Button, Classes, InputGroup, Intent, NonIdealState, Spinner } from "@blueprintjs/core";
import { useMemo, useRef, useState } from "react";
import { api, isAbortError } from "../api/client";
import type { SearchRequest, SearchResponse } from "../api/types";
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
  /** the response shown, with the query and correction setting it answers (not the request in flight) */
  const [shown, setShown] = useState<{ typed: string; response: SearchResponse } | null>(null);
  /** settings of the last request, for retrying it after an error */
  const [last, setLast] = useState<{ query: string; correct: boolean } | null>(null);
  const results = shown?.response.results ?? null;
  const corrected = shown?.response.correctedQuery ?? null;
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>();
  const ctrlRef = useRef<AbortController | null>(null);

  // highlight what was actually searched for
  const searchedFor = corrected ?? shown?.typed ?? submitted;
  const terms = useMemo(() => (searchedFor ? queryTerms(searchedFor) : []), [searchedFor]);

  /** `correct: false` searches for the query exactly as typed ("search instead for …"). */
  const run = async (q = query, correct = true) => {
    const text = q.trim();
    if (!text) return;
    ctrlRef.current?.abort();
    const ctrl = new AbortController();
    ctrlRef.current = ctrl;
    setLoading(true);
    setError(undefined);
    setSubmitted(text);
    setLast({ query: text, correct });
    setReranked(scope.rerank && !!system?.rerankEnabled && !!system.rerankerReachable);
    const req: SearchRequest = {
      query: text,
      topK: scope.topK,
      ...(scope.books.length ? { bookIds: scope.books.map((b) => b.id) } : {}),
      ...(scope.tags.length ? { tags: scope.tags } : {}),
      rerank: scope.rerank,
      ...(correct ? {} : { correct: false }),
    };
    try {
      const response = await api.search(req, ctrl.signal);
      setShown({ typed: text, response });
    } catch (err) {
      if (!isAbortError(err)) setError(err);
    } finally {
      if (ctrlRef.current === ctrl) setLoading(false);
    }
  };

  const bookCount = results ? new Set(results.map((r) => r.bookId)).size : 0;
  // hidden while another search loads: its link would act on a query that isn't the one shown
  const notice = corrected && shown && !loading && (
    <CorrectionNotice corrected={corrected} typed={shown.typed} onSearchTyped={() => void run(shown.typed, false)} />
  );

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
        <ScopeBar />
      </div>

      <div className="search-results">
        {error ? (
          <ErrorState
            error={error}
            onRetry={() => void (last ? run(last.query, last.correct) : run())}
            title="Search failed"
          />
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
          <>
            {notice}
            <NonIdealState
              icon="inbox-search"
              title="No passages found"
              description={
                reranked
                  ? "Nothing in the library looked relevant enough to the reranker. Try other terms, widen the scope, or turn reranking off."
                  : "Try other terms or widen the scope."
              }
            />
          </>
        ) : (
          <>
            {notice}
            <div className={`${Classes.TEXT_MUTED} ${Classes.TEXT_SMALL} results-summary`}>
              {results.length} passages from {bookCount} book{bookCount === 1 ? "" : "s"} for “{searchedFor}”
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

/** "Showing results for <corrected>. Search instead for <typed>" after a spelling correction. */
function CorrectionNotice({
  corrected,
  typed,
  onSearchTyped,
}: {
  corrected: string;
  typed: string;
  onSearchTyped: () => void;
}) {
  return (
    <div className="correction-notice" role="status">
      <div>
        Showing results for <strong>{corrected}</strong>
      </div>
      <div className={Classes.TEXT_SMALL}>
        Search instead for{" "}
        <button type="button" className="correction-original" onClick={onSearchTyped}>
          {typed}
        </button>
      </div>
    </div>
  );
}
