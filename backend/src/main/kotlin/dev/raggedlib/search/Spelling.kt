package dev.raggedlib.search

/**
 * Pure parts of query spelling correction (the database side is in [SpellingCorrector]): which words of a query
 * may be corrected, candidate generation, edit distance, choosing a correction and writing the corrected query.
 */
object Spelling {
    const val MAX_WORD_LENGTH = 40

    /** A correctable word of the query: [text] as typed, at [start] until [end] (exclusive). */
    data class Token(val text: String, val start: Int, val end: Int) {
        val lower: String get() = text.lowercase()
    }

    /** A vocabulary word that could replace a typed one; [sameStem]: it has the typed word's full-text lexeme. */
    data class Candidate(val word: String, val docs: Int, val sameStem: Boolean = false)

    /**
     * How often a typed word occurs: [typedDocs] passages contain it as typed (0 if under the vocabulary's floor),
     * [stemDocs] contain its lexeme (any inflection).
     */
    data class Occurrence(val typedDocs: Long, val stemDocs: Long) {
        /** In no passage at all: almost certainly a typo, or a name the library doesn't mention. */
        val unknown: Boolean get() = typedDocs == 0L && stemDocs == 0L
    }

    /** Thresholds of [choose]; see `raggedlib.search.spelling`. */
    data class Rules(val longWordLength: Int, val minCandidateDocs: Int, val minFrequencyRatio: Double)

    /**
     * Words made of letters only, standing on their own. Left alone: anything touching digits, `_ . - / @ : # + $`
     * and similar (identifiers, file names, `-exclusions`, `tf.GradientTape`, `std::move`), since the full-text
     * parser indexes those as other tokens; camelCase and ALL-CAPS words (identifiers, acronyms); and words
     * shorter than [minLength], where one edit turns most words into another real one.
     */
    fun tokens(query: String, minLength: Int): List<Token> = WORD.findAll(query)
        .map { Token(it.value, it.range.first, it.range.last + 1) }
        .filter { it.text.length in minLength..MAX_WORD_LENGTH }
        .filter { t -> t.text.drop(1).none { it.isUpperCase() } }
        .toList()

    // A word is preceded by start/space/punctuation, not by a character that makes it part of a larger token.
    // A trailing '.' or ':' only counts as sentence punctuation when no word character follows it. Quotes count as
    // punctuation, except an apostrophe between letters: contractions (don't) are skipped, possessives ('s) kept.
    private val WORD = Regex(
        """(?<![\p{L}\p{N}_.\-/@:#+$\\`~])(?<!\p{L}['’])\p{L}+""" +
            """(?![\p{L}\p{N}_\-/@#+$\\`~(\[]|[.:][\p{L}\p{N}_]|['’](?![sS](?![\p{L}\p{N}]))\p{L})""",
    )

    /**
     * Every string one edit from [word]: a deletion, an adjacent transposition, a replacement or an insertion,
     * using a–z plus the word's own letters (so non-ASCII words get their own alphabet).
     */
    fun edits1(word: String): Set<String> {
        val letters = (('a'..'z') + word.toList()).distinct()
        val out = HashSet<String>()
        for (i in 0..word.length) {
            val left = word.substring(0, i)
            val right = word.substring(i)
            if (right.isNotEmpty()) out += left + right.substring(1)
            if (right.length > 1) out += left + right[1] + right[0] + right.substring(2)
            for (c in letters) {
                if (right.isNotEmpty() && c != right[0]) out += left + c + right.substring(1)
                out += left + c + right
            }
        }
        out -= word
        return out
    }

    /** Optimal-string-alignment distance: Levenshtein plus adjacent transpositions as one edit. */
    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }

    /**
     * Best replacement for a typed [word] (lower case) occurring as [occurrence], or null to leave it. Among the
     * allowed candidates the closest wins, then the most frequent. Allowed:
     *  - always: at least [Rules.minCandidateDocs] passages, and the same first letter (or the first two swapped):
     *    typos rarely hit the first letter, while names differ there ("Niri" is not "Siri");
     *  - for an [Occurrence.unknown] word: one edit, two from [Rules.longWordLength] letters;
     *  - for a word the library contains, which may be a rare real word rather than a typo: one edit, and
     *    [Rules.minFrequencyRatio] times as frequent as the word — as typed when the candidate has the same lexeme
     *    ("debuging" -> debugging changes nothing for keyword search), else as its lexeme ("minced" stays, though
     *    "minded" is common; "recieve" in 24 passages becomes receive, in 11000).
     */
    fun choose(word: String, occurrence: Occurrence, candidates: Collection<Candidate>, rules: Rules): Candidate? {
        val maxEdits = if (occurrence.unknown && word.length >= rules.longWordLength) 2 else 1
        return candidates.asSequence()
            .filter { it.word != word && it.docs >= rules.minCandidateDocs && sameStart(word, it.word) }
            .filter { c ->
                occurrence.unknown || c.docs >= rules.minFrequencyRatio *
                    maxOf(1L, if (c.sameStem) occurrence.typedDocs else maxOf(occurrence.typedDocs, occurrence.stemDocs))
            }
            .map { it to distance(word, it.word) }
            .filter { (_, d) -> d in 1..maxEdits }
            .minWithOrNull(compareBy<Pair<Candidate, Int>>({ it.second }, { -it.first.docs }, { it.first.word }))
            ?.first
    }

    /** Same first letter, or the first two letters swapped ("rpogram" -> program). */
    fun sameStart(a: String, b: String): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && (a[0] == b[0] || (a.length > 1 && b.length > 1 && a[0] == b[1] && a[1] == b[0]))

    /** [corrected] (lower case) in the case pattern of [typed]: lower, Capitalised or as is. */
    fun matchCase(typed: String, corrected: String): String =
        if (typed.firstOrNull()?.isUpperCase() == true) corrected.replaceFirstChar { it.uppercaseChar() } else corrected

    /** [query] with each token in [replacements] (by start offset) replaced. */
    fun apply(query: String, replacements: Map<Token, String>): String {
        val sb = StringBuilder(query)
        replacements.entries.sortedByDescending { it.key.start }.forEach { (t, r) -> sb.replace(t.start, t.end, r) }
        return sb.toString()
    }
}
