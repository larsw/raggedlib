package dev.raggedlib.search

import kotlin.math.ln

/**
 * Planning for the keyword *fallback* pass, used when the strict all-terms query finds too little.
 *
 * Natural-language questions rarely contain every term in one passage, so the fallback relaxes AND to
 * "enough of the important terms". Importance is BM25 IDF: in a programming library `program` or `return`
 * occur in ~15% of chunks and say little, while `gadget` (0.06%) says a lot. Hence:
 *  - candidates must contain at least one non-common term (doc frequency ≤ [commonMaxFrequency]); a
 *    query made only of common terms gets no fallback (the vector side covers it), which also keeps the
 *    candidate set small;
 *  - candidates are scored by *coverage*: the IDF-weighted share of query terms they contain, and must
 *    reach a minimum (so "orient"+"program" alone can't stand in for "return oriented programming gadgets").
 *
 * Pure functions; the SQL lives in [SearchService], statistics in [TermStatistics].
 */
object KeywordFallback {

    data class Term(val lexeme: String, val weight: Double)

    data class Plan(
        /** Query terms that take part in scoring, rarest first. */
        val terms: List<Term>,
        /** tsquery text: OR of the non-common terms — the candidate filter (GIN-indexable). */
        val candidateQuery: String,
        /** tsquery text: OR of all scored terms — only used to break coverage ties with ts_rank_cd. */
        val rankQuery: String,
    ) {
        val totalWeight: Double get() = terms.sumOf { it.weight }
    }

    /**
     * @param frequencies lexeme → fraction of chunks containing it (0..1)
     * @param totalDocs number of chunks, for IDF
     * @return null when there is nothing worth a fallback (no terms, or only common ones)
     */
    fun plan(frequencies: Map<String, Double>, totalDocs: Double, commonMaxFrequency: Double, maxTerms: Int): Plan? {
        // A term that occurs nowhere (typo, unknown word) can't match any passage. Left in, it would get the
        // largest IDF of all and inflate the total, so no passage could reach the coverage threshold.
        val present = frequencies.filterValues { it > 0.0 }
        if (present.isEmpty()) return null
        // keep the most discriminative terms; long questions otherwise drag in noise words
        val kept = present.entries.sortedWith(compareBy({ it.value }, { it.key })).take(maxTerms.coerceAtLeast(1))
        val rare = kept.filter { it.value <= commonMaxFrequency }
        if (rare.isEmpty()) return null
        val terms = kept.map { Term(it.key, idf(it.value, totalDocs)) }
        return Plan(
            terms = terms,
            candidateQuery = rare.joinToString(" | ") { tsQueryLiteral(it.key) },
            rankQuery = terms.joinToString(" | ") { tsQueryLiteral(it.lexeme) },
        )
    }

    /** BM25 IDF: ln(1 + (N − n + 0.5) / (n + 0.5)), always positive. */
    fun idf(frequency: Double, totalDocs: Double): Double {
        val n = totalDocs.coerceAtLeast(1.0)
        val df = (frequency.coerceIn(0.0, 1.0) * n)
        return ln(1 + (n - df + 0.5) / (df + 0.5))
    }

    /**
     * Quotes an already-normalised lexeme as a tsquery operand, so `cast(? as tsquery)` takes it verbatim
     * (no parsing of `&|!():<*` and no second stemming pass).
     */
    fun tsQueryLiteral(lexeme: String): String = "'" + lexeme.replace("\\", "\\\\").replace("'", "''") + "'"

    private val WEBSEARCH_OPERATORS = Regex(""""[^"]*"|(^|\s)-\S|\sor\s""", RegexOption.IGNORE_CASE)

    /**
     * True if the user wrote websearch syntax: a quoted phrase (a lone `"`, as in `5" floppy`, doesn't count),
     * a `-exclusion` or ` or `. The strict pass honours it; the fallback can't (it works on bare terms), so it
     * is skipped rather than silently ignore an exclusion.
     */
    fun usesWebsearchSyntax(query: String): Boolean = WEBSEARCH_OPERATORS.containsMatchIn(query)
}
