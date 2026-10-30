package tech.devline.scropy_ui.adb

import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val deadlineTimer = Executors.newSingleThreadScheduledExecutor { task ->
    Thread(task, "adb-deadline").apply { isDaemon = true }
}

/** Closing the resource, not coroutine cancellation alone, unblocks ADB reads. */
internal fun <T> withAdbDeadline(timeoutMs: Long, abort: () -> Unit, block: () -> T): T {
    require(timeoutMs > 0)
    val state = AtomicInteger(0) // active / completed / timed out
    val timeout = deadlineTimer.schedule({
        if (state.compareAndSet(0, 2)) runCatching(abort)
    }, timeoutMs, TimeUnit.MILLISECONDS)
    try {
        val result = block()
        if (!state.compareAndSet(0, 1)) throw SocketTimeoutException("ADB operation timed out")
        return result
    } catch (e: Exception) {
        if (state.get() == 2) throw SocketTimeoutException("ADB operation timed out").apply { initCause(e) }
        throw e
    } finally {
        state.compareAndSet(0, 1)
        timeout.cancel(false)
    }
}
