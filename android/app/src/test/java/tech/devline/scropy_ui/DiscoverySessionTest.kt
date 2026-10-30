package tech.devline.scropy_ui

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscoverySessionTest {
    @Test fun stoppedOrReplacedScanCannotPublishLateCallbacks() {
        val session = DiscoverySession()
        val first = session.begin()
        assertTrue(session.accepts(first))
        session.stop()
        assertFalse(session.accepts(first))
        val second = session.begin()
        assertFalse(session.accepts(first))
        assertTrue(session.accepts(second))
        session.stop()
        assertFalse(session.accepts(second))
    }

    @Test fun scansEachCandidateOnceWithinWorkerLimit() = runBlocking {
        val candidates = Collections.synchronizedList(mutableListOf<Int>())
        val active = AtomicInteger()
        val peak = AtomicInteger()
        scanRange(1..20, workerCount = 3) { candidate ->
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try {
                delay(1)
                candidates.add(candidate)
            } finally {
                active.decrementAndGet()
            }
        }
        assertEquals((1..20).toList(), candidates.sorted())
        assertTrue(peak.get() <= 3)
        assertEquals(0, active.get())
    }

    @Test fun cancellingScanStopsWorkersWithoutStartingMoreProbes() = runBlocking {
        val started = AtomicInteger()
        val finished = AtomicInteger()
        val workersReady = CompletableDeferred<Unit>()
        val job = launch {
            scanRange(1..100, workerCount = 4) {
                if (started.incrementAndGet() == 4) workersReady.complete(Unit)
                try { awaitCancellation() } finally { finished.incrementAndGet() }
            }
        }
        workersReady.await()
        job.cancelAndJoin()
        assertEquals(4, started.get())
        assertEquals(4, finished.get())
    }

    @Test fun emptyRangeDoesNotProbe() = runBlocking {
        scanRange(1..0, workerCount = 2) { fail("Empty range must not be probed") }
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroWorkersAreRejected() = runBlocking {
        scanRange(1..2, workerCount = 0) { }
    }
}
