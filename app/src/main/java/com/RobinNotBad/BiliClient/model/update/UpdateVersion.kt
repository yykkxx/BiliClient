package com.RobinNotBad.BiliClient.model.update

/** Date-based versionCode cannot distinguish two releases on the same day. */
object UpdateVersion {
    private val versionPattern = Regex(
        "^[vV]?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-([A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*))?(?:\\+[A-Za-z0-9.-]+)?$"
    )
    private val identifierPattern = Regex("[A-Za-z]+|[0-9]+")

    /** Positive = remote newer; null = unknown format, never pretend it is up to date. */
    fun compare(remote: String, installed: String): Int? {
        val left = parse(remote) ?: return null
        val right = parse(installed) ?: return null
        for (index in left.numbers.indices) {
            val order = compareNumber(left.numbers[index], right.numbers[index])
            if (order != 0) return order
        }
        return compareStage(left.stage, right.stage)
    }

    fun releaseVersion(tag: String): String? {
        val trimmed = tag.trim()
        if (parse(trimmed) == null) return null
        return trimmed.removePrefix("v").removePrefix("V")
    }

    private data class Version(val numbers: List<String>, val stage: List<String>)

    private fun parse(text: String): Version? {
        val match = versionPattern.matchEntire(text.trim()) ?: return null
        val stage = identifierPattern.findAll(match.groupValues[4]).map { it.value.lowercase() }.toList()
        return Version(match.groupValues.subList(1, 4), stage)
    }

    private fun compareStage(left: List<String>, right: List<String>): Int {
        if (left.isEmpty() && right.isEmpty()) return 0
        if (left.isEmpty()) return 1
        if (right.isEmpty()) return -1
        for (index in 0 until minOf(left.size, right.size)) {
            val order = compareIdentifier(left[index], right[index])
            if (order != 0) return order
        }
        return left.size.compareTo(right.size)
    }

    private fun compareIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all { it.isDigit() }
        val rightNumeric = right.all { it.isDigit() }
        if (leftNumeric && rightNumeric) return compareNumber(left, right)
        if (leftNumeric != rightNumeric) return if (leftNumeric) -1 else 1
        return left.compareTo(right)
    }

    // Compare unbounded digit strings without overflow (also accepts historic leading zeroes).
    private fun compareNumber(left: String, right: String): Int {
        val first = left.trimStart('0').ifEmpty { "0" }
        val second = right.trimStart('0').ifEmpty { "0" }
        val lengthOrder = first.length.compareTo(second.length)
        return if (lengthOrder != 0) lengthOrder else first.compareTo(second)
    }
}
