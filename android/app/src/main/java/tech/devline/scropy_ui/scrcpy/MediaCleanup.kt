package tech.devline.scropy_ui.scrcpy

/** A codec that never reached start() may reject stop() but still needs release(). */
internal fun releaseMediaResource(stop: () -> Unit, release: () -> Unit) {
    runCatching(stop)
    runCatching(release)
}
