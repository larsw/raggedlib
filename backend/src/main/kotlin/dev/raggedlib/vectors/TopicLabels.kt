package dev.raggedlib.vectors

import kotlin.math.ln

/**
 * Class-based TF-IDF (Grootendorst, "BERTopic", 2022): instead of ranking a term by how rare it is across
 * documents, treat each cluster as one document and rank terms by how much they stand out in *that* cluster
 * compared to the others. The words themselves come from Postgres's own tokenisation (`content_tsv` is
 * already stemmed and stopword-filtered, see V1__init.sql), not a tokenizer here.
 */
object TopicLabels {
    /**
     * For each cluster in [wordCounts] (word → occurrences within that cluster), the [topN] words with the
     * highest c-TF-IDF score: `tf(word, cluster) * ln(1 + A / tf(word))`, where `tf(word)` is the word's total
     * count over every cluster and `A` the average word count per cluster — a word common to every cluster (even
     * a content word, not a stopword: those are already gone from `content_tsv`) scores low everywhere, one
     * concentrated in a single cluster scores high there. Ties break on raw count, then the word itself, for a
     * stable order. A cluster with no words gets an empty list, never an error.
     */
    fun label(wordCounts: Map<Int, Map<String, Int>>, topN: Int = 5): Map<Int, List<String>> {
        val numClusters = wordCounts.size
        if (numClusters == 0) return emptyMap()
        val totalFrequency = HashMap<String, Int>()
        var totalWords = 0
        for (words in wordCounts.values) for ((w, c) in words) {
            totalFrequency.merge(w, c, Int::plus)
            totalWords += c
        }
        val averageWordsPerCluster = totalWords.toDouble() / numClusters

        return wordCounts.mapValues { (_, words) ->
            words.entries
                .map { (word, tf) -> word to tf * ln(1.0 + averageWordsPerCluster / totalFrequency.getValue(word)) }
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenByDescending { words.getValue(it.first) }.thenBy { it.first })
                .take(topN)
                .map { it.first }
        }
    }
}
