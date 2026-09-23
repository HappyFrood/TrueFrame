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

        // Metadata 90 on portrait phone video (metaW=1080, metaH=1920 -> dispW=1920, dispH=1080)
        // If retriever already returned display-oriented bitmap (1920x1080), rawBitmap.width > rawBitmap.height matches dispW > dispH -> false
        assertFalse(FrameExporter.needsMetaRotation(1920, 1080, 90, 1080, 1920))

        // If retriever returned raw un-rotated bitmap (1080x1920), rawBitmap width < height disagrees with dispW > dispH -> true
        assertTrue(FrameExporter.needsMetaRotation(1080, 1920, 90, 1080, 1920))

        // Square frame -> false
        assertFalse(FrameExporter.needsMetaRotation(1000, 1000, 90, 1000, 1000))
    }
}
