package tech.devline.scropy_ui

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Invalidates callbacks that Android may deliver after discovery was stopped. */
internal class DiscoverySession {
    @Volatile
    private var generation = 0L
    @Volatile
    private var active = false

    fun begin(): Long {
        active = true
        return ++generation
    }

    fun stop() {
        active = false
        generation++
    }

    fun accepts(token: Long): Boolean = active && token == generation
}

/** Keep port discovery bounded instead of creating one coroutine per port. */
internal suspend fun scanRange(
    range: IntRange,
    workerCount: Int,
    probe: suspend (Int) -> Unit,
) = coroutineScope {
    require(workerCount > 0)
    val next = AtomicInteger(range.first)
    repeat(workerCount) {
        launch {
            while (true) {
                currentCoroutineContext().ensureActive()
                val candidate = next.getAndIncrement()
                if (candidate > range.last) break
                probe(candidate)
            }
        }
    }
}
