package dev.raggedlib.search

import dev.raggedlib.config.RaggedlibProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import kotlin.math.roundToLong

/**
 * Corrects misspelled query words before searching, from the library's own vocabulary ([SpellingVocabulary]), so
 * the keyword search, the embedding and the reranker all get the intended word. Which words count as misspelled,
 * and what may replace them, is decided by [Spelling.choose]: a word found nowhere in the library is corrected to
 * the closest frequent word; a word the library does contain only when a one-edit neighbour is overwhelmingly more
 * frequent (the classic typos that books contain too). Only words in at most `typo-max-docs` passages as typed are
 * looked at, so ordinary words cost one small query.
 */
@Component
class SpellingCorrector(
    private val vocabulary: SpellingVocabulary,
    private val termStats: TermStatistics,
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: RaggedlibProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The query with its misspelled words corrected, or null when nothing needed (or could get) a correction. */
    fun correct(query: String): CorrectedQuery? {
        val s = props.search.spelling
        if (!s.enabled || !vocabulary.ready || query.length > props.search.maxKeywordQueryChars) return null
        return try {
            correctOrNull(query, s)
        } catch (e: Exception) {
            // never fail a search over a correction
            log.warn("Spelling correction failed for '{}': {}", query.take(100), e.message)
            null
        }
    }

    private fun correctOrNull(query: String, s: RaggedlibProperties.Spelling): CorrectedQuery? {
        val tokens = Spelling.tokens(query, s.minWordLength).take(MAX_WORDS)
        if (tokens.isEmpty()) return null
        val words = tokens.map { it.lower }.distinct()

        // as typed (vocabulary) first: most query words are common, and those need nothing more
        val typedDocs = vocabulary.lookup(words).associate { it.word to it.docs.toLong() }
        val rare = words.filter { (typedDocs[it] ?: 0L) <= s.typoMaxDocs }
        if (rare.isEmpty()) return null
        // stop words have no lexeme and are never corrected
        val lexemeOf = lexemes(rare)
        if (lexemeOf.isEmpty()) return null
        val frequency = termStats.frequencies(lexemeOf.values.distinct())
        val totalDocs = termStats.totalDocs()
        val occurrence = lexemeOf.mapValues { (word, lexeme) ->
            Spelling.Occurrence(typedDocs[word] ?: 0L, ((frequency[lexeme] ?: 0.0) * totalDocs).roundToLong())
        }

        val edits = occurrence.keys.associateWith { Spelling.edits1(it) }
        val known = vocabulary.lookup(edits.values.flatten().toSet()).associate { it.word to it.docs }
        val candidates = occurrence.mapValues { (word, occ) ->
            val near = edits.getValue(word).mapNotNull { w -> known[w]?.let { w to it } }.toMap(HashMap())
            if (occ.unknown && word.length >= s.longWordLength) {
                vocabulary.similar(word, SIMILAR_LIMIT).forEach { near.putIfAbsent(it.word, it.docs) }
            }
            near
        }
        // the lexeme of every candidate, to tell "debugging" (same lexeme as "debuging") from "minded" ("minced")
        val candidateLexeme = lexemes(candidates.values.flatMap { it.keys }.distinct())
        val rules = Spelling.Rules(s.longWordLength, s.minCandidateDocs, s.minFrequencyRatio)
        val fixes = occurrence.mapNotNull { (word, occ) ->
            val options = candidates.getValue(word).map { (w, docs) ->
                Spelling.Candidate(w, docs, sameStem = candidateLexeme[w] == lexemeOf[word])
            }
            Spelling.choose(word, occ, options, rules)?.let { word to it }
        }.toMap()
        if (fixes.isEmpty()) return null

        val replacements = tokens.mapNotNull { t -> fixes[t.lower]?.let { t to Spelling.matchCase(t.text, it.word) } }.toMap()
        val corrections = replacements.map { (t, r) ->
            SpellingCorrection(from = t.text, to = r, known = !occurrence.getValue(t.lower).unknown)
        }.distinct()
        val corrected = Spelling.apply(query, replacements)
        log.info("Spelling: '{}' -> '{}'", query.take(200), corrected.take(200))
        return CorrectedQuery(corrected, corrections)
    }

    /** word -> its English full-text lexeme; words without one (stop words) are left out. */
    private fun lexemes(words: Collection<String>): Map<String, String> {
        if (words.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        jdbc.query(
            "select t, (tsvector_to_array(to_tsvector('english', t)))[1] from unnest(cast(:words as text[])) t",
            MapSqlParameterSource("words", words.toTypedArray()),
        ) { rs -> rs.getString(2)?.let { out[rs.getString(1)] = it } }
        return out
    }

    private companion object {
        /** Words looked at per query; a pasted paragraph isn't a typed query. */
        const val MAX_WORDS = 24
        const val SIMILAR_LIMIT = 40
    }
}

data class CorrectedQuery(val query: String, val corrections: List<SpellingCorrection>)
