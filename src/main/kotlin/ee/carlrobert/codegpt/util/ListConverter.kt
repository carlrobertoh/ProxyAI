package ee.carlrobert.codegpt.util

import com.fasterxml.jackson.core.type.TypeReference

class ListConverter : BaseConverter<List<Any>>(object : TypeReference<List<Any>>() {})

/**
 * Trims the [MutableList] to [maxSize]
 */
fun <T> MutableList<T>.trimToSize(maxSize: Int): Boolean {
    var size = this.size
    val trim = size > 0 && size > maxSize
    when {
        trim && maxSize <= 0 -> clear()
        trim -> while (size > maxSize) removeAt(--size)
    }

    return trim
}
