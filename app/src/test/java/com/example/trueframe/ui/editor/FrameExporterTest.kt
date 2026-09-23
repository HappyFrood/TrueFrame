package com.example.trueframe.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameExporterTest {

    @Test
    fun testNeedsMetaRotationDecision() {
        // Metadata 0 or 180 degrees -> no rotation needed
        assertFalse(FrameExporter.needsMetaRotation(1920, 1080, 0, 1920, 1080))
        assertFalse(FrameExporter.needsMetaRotation(1920, 1080, 180, 1920, 1080))

        // Real-world portrait phone clip (stored as 1920x1080 with rotation 90)
        // Already rotated by platform JNI -> rawBitmap is 1080x1920 -> false
        assertFalse(FrameExporter.needsMetaRotation(1080, 1920, 90, 1920, 1080))
        // Raw bitmap un-rotated (1920x1080) -> needs rotation -> true
        assertTrue(FrameExporter.needsMetaRotation(1920, 1080, 90, 1920, 1080))

        // Square frame -> false
        assertFalse(FrameExporter.needsMetaRotation(1000, 1000, 90, 1000, 1000))
    }
}
