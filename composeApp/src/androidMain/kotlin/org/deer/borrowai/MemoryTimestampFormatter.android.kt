package org.deer.borrowai

import java.text.DateFormat
import java.util.Date

internal actual fun formatMemoryTimestamp(createdAtEpochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(createdAtEpochMillis))
