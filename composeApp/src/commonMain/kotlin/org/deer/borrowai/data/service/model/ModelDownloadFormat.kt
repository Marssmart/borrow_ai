package org.deer.borrowai.data.service.model

/** Formats byte counts as whole megabytes (1 MB = 1,000,000 bytes) with thousands separators, e.g. "1,708". */
object ModelDownloadFormat {
    private const val BYTES_PER_MEGABYTE = 1_000_000L

    fun megabytes(bytes: Long): String {
        require(bytes >= 0L) { "A byte count cannot be negative." }
        val digits = (bytes / BYTES_PER_MEGABYTE).toString()
        val grouped = StringBuilder()
        digits.forEachIndexed { index, digit ->
            if (index > 0 && (digits.length - index) % 3 == 0) grouped.append(',')
            grouped.append(digit)
        }
        return grouped.toString()
    }
}
