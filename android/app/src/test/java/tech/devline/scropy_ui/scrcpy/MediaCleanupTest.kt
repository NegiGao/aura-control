package tech.devline.scropy_ui.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCleanupTest {
    @Test fun stillReleasesAnUnstartedCodecWhenStopFails() {
        var releases = 0
        releaseMediaResource({ throw IllegalStateException("Codec never started") }, { releases++ })
        assertEquals(1, releases)
    }

    @Test fun cleanupDoesNotThrowOnAnAlreadyReleasedResource() {
        releaseMediaResource({ throw IllegalStateException() }, { throw IllegalStateException() })
    }
}
