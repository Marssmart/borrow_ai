package org.deer.borrowai

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970

internal actual fun formatMemoryTimestamp(createdAtEpochMillis: Long): String {
    val formatter = NSDateFormatter()
    formatter.locale = NSLocale.currentLocale
    formatter.dateStyle = NSDateFormatterMediumStyle
    formatter.timeStyle = NSDateFormatterShortStyle
    return formatter.stringFromDate(
        NSDate.dateWithTimeIntervalSince1970(createdAtEpochMillis.toDouble() / MILLIS_PER_SECOND),
    )
}

private const val MILLIS_PER_SECOND = 1_000.0
