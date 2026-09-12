package com.example.trueframe.data

import androidx.compose.ui.geometry.Offset
import com.example.trueframe.core.annotation.AnnotationShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationJsonTest {

    @Test
    fun serializeAndDeserialize_roundTrip() {
        val line = AnnotationShape.Line(Offset(10.5f, 20.5f), Offset(100f, 200f))
        val serialized = AnnotationJson.serialize(line)
        val deserialized = AnnotationJson.deserialize(serialized)

        assertNotNull(deserialized)
        assertTrue(deserialized is AnnotationShape.Line)
        val resultLine = deserialized as AnnotationShape.Line
        assertEquals(line.start.x, resultLine.start.x, 0.01f)
        assertEquals(line.start.y, resultLine.start.y, 0.01f)
        assertEquals(line.end.x, resultLine.end.x, 0.01f)
        assertEquals(line.end.y, resultLine.end.y, 0.01f)
    }

    @Test
    fun deserialize_invalidJson_returnsNullWithoutException() {
        val malformedJson = "{ \"type\": \"invalid_shape\" }"
        val result = AnnotationJson.deserialize(malformedJson)
        assertNull(result)
    }
}
