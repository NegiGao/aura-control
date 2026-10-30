package tech.devline.scropy_ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import tech.devline.scropy_ui.adb.DeviceIdentityException

class ConnectionRecoveryTest {
    @Test fun retriesTheFailedConnectionUntilTheThirdAttempt() = runBlocking {
        val attempts = mutableListOf<Int>()
        var calls = 0
        val result = retryConnectionOperation(3, { true }, { attempts.add(it) }) {
            if (++calls < 3) throw IOException("Network unavailable")
            "connected"
        }
        assertEquals("connected", result)
        assertEquals(listOf(1, 2, 3), attempts)
    }

    @Test fun stopsAfterThreeFailures() = runBlocking {
        var calls = 0
        try {
            retryConnectionOperation(3, { true }, {}) { calls++; throw IOException("Offline") }
            fail("Must fail")
        } catch (_: IOException) { assertEquals(3, calls) }
    }

    @Test fun doesNotRetryTheWrongDevice() = runBlocking {
        var calls = 0
        try {
            retryConnectionOperation(3, { it !is DeviceIdentityException }, {}) {
                calls++; throw DeviceIdentityException("Wrong serial")
            }
            fail("Must fail")
        } catch (_: DeviceIdentityException) { assertEquals(1, calls) }
    }

    @Test fun cancellationDoesNotStartAnotherConnection() = runBlocking {
        var calls = 0
        try {
            retryConnectionOperation(3, { true }, {}) { calls++; throw CancellationException() }
            fail("Must cancel")
        } catch (_: CancellationException) { assertEquals(1, calls) }
    }

    @Test fun commandsKeepClickOrderDespiteDifferentDurationsAndOneFailure() = runBlocking {
        val runner = OrderedCommandRunner()
        val started = mutableListOf<Int>()
        val finished = mutableListOf<Int>()
        val jobs = (0..4).map { value ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    runner.run {
                        started.add(value)
                        delay((5 - value) * 5L)
                        if (value == 2) throw IOException("One command failed")
                        finished.add(value)
                    }
                } catch (_: IOException) {}
            }
        }
        jobs.forEach { it.join() }
        assertEquals(listOf(0, 1, 2, 3, 4), started)
        assertEquals(listOf(0, 1, 3, 4), finished)
    }

    @Test fun cancellingAQueuedCommandDoesNotBlockTheNextOne() = runBlocking {
        val runner = OrderedCommandRunner()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { runner.run { delay(50) } }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { runner.run { fail("Cancelled command ran") } }
        cancelled.cancel()
        var lastRan = false
        val last = launch(start = CoroutineStart.UNDISPATCHED) { runner.run { lastRan = true } }
        first.join(); cancelled.join(); last.join()
        assertTrue(lastRan)
    }

    @Test fun audioAndCodecInitializationFailuresDoNotEscapeTheWorker() = runBlocking {
        listOf(EOFException("Audio config missing"), IllegalStateException("Codec unavailable"),
            IllegalArgumentException("Unsupported codec format")).forEach { failure ->
            var received: Exception? = null
            runMediaWorker({ received = it }) { throw failure }
            assertSame(failure, received)
        }
    }

    @Test fun mediaCancellationIsNotReportedAsAPlaybackFailure() = runBlocking {
        try {
            runMediaWorker({ fail("Cancellation must propagate") }) { throw CancellationException() }
            fail("Must cancel")
        } catch (_: CancellationException) {}
    }

    @Test fun videoFitsBothLandscapeAndPortraitWithoutCropping() {
        assertEquals(1920 to 1200, fitVideoSize(1920, 1200, 16f / 10f))
        assertEquals(750 to 1200, fitVideoSize(1920, 1200, 10f / 16f))
        assertEquals(1920 to 600, fitVideoSize(1920, 1200, 32f / 10f))
        assertEquals(1080 to 675, fitVideoSize(1080, 1920, 16f / 10f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidVideoAspect() { fitVideoSize(1920, 1200, Float.NaN) }

    @Test fun leftVideoViewportKeepsFullHeightWithoutToolbarOrCropping() {
        assertEquals(640 to 400, fitVideoSize(720, 400, 16f / 10f))
        assertEquals(450 to 281, fitVideoSize(450, 400, 16f / 10f))
        assertEquals(720 to 225, fitVideoSize(720, 400, 32f / 10f))
        assertEquals(250 to 400, fitVideoSize(720, 400, 10f / 16f))
    }
}
