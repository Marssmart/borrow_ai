package org.deer.borrowai.data.domain

fun interface EpochClock {
    fun nowEpochMillis(): Long
}
