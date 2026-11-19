package dev.raggedlib.library

data class MetadataMergeResult(
    val conflicts: Map<String, Any?>,
    val changes: Map<String, Any?>,
)

/** Reconciles only fields the editor changed; unrelated Calibre updates are preserved. */
internal fun mergeMetadataFields(
    original: Map<String, Any?>,
    current: Map<String, Any?>,
    requested: Map<String, Any?>,
): MetadataMergeResult {
    val conflicts = requested.keys
        .filter { original[it] != current[it] }
        .associateWith { current[it] }
    if (conflicts.isNotEmpty()) return MetadataMergeResult(conflicts, emptyMap())
    return MetadataMergeResult(
        conflicts = emptyMap(),
        changes = requested.filter { (field, value) -> current[field] != value },
    )
}
