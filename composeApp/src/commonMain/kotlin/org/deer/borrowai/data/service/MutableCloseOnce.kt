package org.deer.borrowai.data.service

import kotlinx.coroutines.flow.MutableStateFlow

class MutableCloseOnce {
    private val closed = MutableStateFlow(false)

    val isClosed: Boolean
        get() = closed.value

    /**
     * Runs [action] only on the first call. Returns true if this call performed the close.
     */
    fun closeOnce(action: () -> Unit): Boolean {
        if (!closed.compareAndSet(expect = false, update = true)) return false
        action()
        return true
    }
}
