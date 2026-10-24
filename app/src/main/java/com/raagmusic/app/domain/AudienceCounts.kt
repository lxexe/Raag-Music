package com.raagmusic.app.domain

fun compactAudienceCount(count: Long): String {
    val (divisor, suffix) = when {
        count >= 1_000_000_000 -> 1_000_000_000.0 to "B"
        count >= 1_000_000 -> 1_000_000.0 to "M"
        count >= 1_000 -> 1_000.0 to "K"
        else -> return count.coerceAtLeast(0).toString()
    }
    return String.format(java.util.Locale.US, "%.1f", count / divisor).removeSuffix(".0") + suffix
}


fun compactMonthlyAudience(label: String): String {
    val match = Regex("^([0-9,.]+)\\s*([KMB]?)\\s+monthly\\s+(listeners|audience|views)$", RegexOption.IGNORE_CASE).find(label.trim()) ?: return label
    val number = match.groupValues[1]
    val suffix = match.groupValues[2].uppercase()
    val count = if (suffix.isNotEmpty()) number + suffix else number.replace(",", "").toLongOrNull()?.let(::compactAudienceCount) ?: number
    return "$count monthly ${match.groupValues[3].lowercase()}"
}
