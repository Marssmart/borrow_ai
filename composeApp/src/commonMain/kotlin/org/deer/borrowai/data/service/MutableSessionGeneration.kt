package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.MutableStateFlow

class MutableSessionGeneration {
    private val mutableGeneration = MutableStateFlow(0L)

    val value: Long
        get() = mutableGeneration.value

    fun isCurrent(generation: Long): Boolean = mutableGeneration.value == generation

    fun safeSet(current: Long, next: Long): Boolean = mutableGeneration.compareAndSet(current, next)

    fun advance(): Long {
        while (true) {
            val current = value
            val next = current + 1L
            if (safeSet(current, next)) return next
        }
    }
}
