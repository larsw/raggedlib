package dev.raggedlib.config

import org.springframework.util.unit.DataSize
import java.util.Locale

/** `500 MB`, `2 GB`, `1.5 GB`: for messages, in the binary units Spring's DataSize uses. */
fun DataSize.label(): String {
    val bytes = toBytes()
    val units = listOf("GB" to DataSize.ofGigabytes(1), "MB" to DataSize.ofMegabytes(1), "KB" to DataSize.ofKilobytes(1))
    for ((unit, size) in units) {
        if (bytes >= size.toBytes()) {
            val value = bytes.toDouble() / size.toBytes()
            val text = if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(Locale.ROOT, value)
            return "$text $unit"
        }
    }
    return "$bytes bytes"
}
