package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.deer.borrowai.data.domain.Memory

/**
 * [MemoryService] that keeps its memories in a [MutableStateFlow] owned by the implementation.
 * Subclasses publish changes through [memories]; callers only see the read-only [getState].
 */
abstract class StatefulMemoryService : MemoryService {
    protected val memories: MutableStateFlow<List<Memory>> = MutableStateFlow(emptyList())

    override fun getState(): StateFlow<List<Memory>> = memories
}
