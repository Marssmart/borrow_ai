package org.deer.borrowai.data.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingWritesTest {
    @Test
    fun awaitAllWaitsForAWriteThatIsStillRunning() = runTest {
        val writes = PendingWrites(backgroundScope)
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        writes.launch {
            release.await()
            events += "write"
        }
        val awaiting = launch {
            writes.awaitAll()
            events += "awaited"
        }
        runCurrent()
        assertEquals(emptyList<String>(), events)

        release.complete(Unit)
        awaiting.join()

        assertEquals(listOf("write", "awaited"), events)
    }

    @Test
    fun awaitAllReturnsAtOnceWhenNothingIsRunning() = runTest {
        PendingWrites(backgroundScope).awaitAll()
    }
}
