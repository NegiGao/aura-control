package tech.devline.scropy_ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal suspend fun <T> retryConnectionOperation(
    attempts: Int,
    canRetry: (Exception) -> Boolean,
    onAttempt: suspend (Int) -> Unit,
    operation: suspend () -> T,
): T {
    require(attempts > 0)
    for (attempt in 1..attempts) {
        currentCoroutineContext().ensureActive()
        onAttempt(attempt)
        try { return operation() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (attempt == attempts || !canRetry(e)) throw e }
    }
    error("Unreachable")
}

/** FIFO lock; failures release it so the next button still works. */
internal class OrderedCommandRunner {
    private val mutex = Mutex()
    suspend fun <T> run(command: suspend () -> T): T = mutex.withLock { command() }
}

internal suspend fun runMediaWorker(onFailure: (Exception) -> Unit, worker: suspend () -> Unit) {
    try { worker() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { onFailure(e) }
}

internal fun fitVideoSize(maxWidth: Int, maxHeight: Int, aspect: Float): Pair<Int, Int> {
    require(maxWidth > 0 && maxHeight > 0 && aspect.isFinite() && aspect > 0)
    val width = minOf(maxWidth, (maxHeight * aspect).toInt().coerceAtLeast(1))
    val height = minOf(maxHeight, (width / aspect).toInt().coerceAtLeast(1))
    return width to height
}
