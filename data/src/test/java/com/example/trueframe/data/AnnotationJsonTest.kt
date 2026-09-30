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

    @Test
    fun arrow_roundTrip() {
        val arrow = AnnotationShape.Arrow(Offset(0.2f, 0.3f), Offset(0.8f, 0.35f))
        val json = AnnotationJson.serialize(arrow)
        assertTrue(json.contains("\"type\""))
        assertEquals(arrow, AnnotationJson.deserialize(json))
    }

    @Test
    fun text_roundTrip_withQuotesCommasAndEmoji() {
        for (content in listOf("Hips \"open\", knees soft", "Nice 🏌️‍♂️ swing!", "a,b,c", "back\\slash")) {
            val text = AnnotationShape.Text(Offset(0.4f, 0.6f), content)
            assertEquals(text, AnnotationJson.deserialize(AnnotationJson.serialize(text)))
        }
    }

    @Test
    fun backCompat_v3JsonForLineAngleCircleStillDecodes() {
        // Exact strings produced by the v3 app (class-name discriminator, "x,y" offsets).
        val line = AnnotationJson.deserialize(
            "{\"type\":\"com.example.trueframe.data.SerializableShape.Line\",\"start\":\"0.3,0.5\",\"end\":\"0.7,0.5\"}"
        )
        assertEquals(AnnotationShape.Line(Offset(0.3f, 0.5f), Offset(0.7f, 0.5f)), line)

        val angle = AnnotationJson.deserialize(
            "{\"type\":\"com.example.trueframe.data.SerializableShape.Angle\",\"start\":\"0.3,0.4\",\"center\":\"0.5,0.5\",\"end\":\"0.7,0.4\"}"
        )
        assertEquals(AnnotationShape.Angle(Offset(0.3f, 0.4f), Offset(0.5f, 0.5f), Offset(0.7f, 0.4f)), angle)

        val circle = AnnotationJson.deserialize(
            "{\"type\":\"com.example.trueframe.data.SerializableShape.Circle\",\"center\":\"0.5,0.5\",\"radius\":0.15}"
        )
        assertEquals(AnnotationShape.Circle(Offset(0.5f, 0.5f), 0.15f), circle)
    }

    @Test
    fun serializedDiscriminators_matchClassNames() {
        val json = AnnotationJson.serialize(AnnotationShape.Line(Offset(0.3f, 0.5f), Offset(0.7f, 0.5f)))
        assertEquals(
            "{\"type\":\"com.example.trueframe.data.SerializableShape.Line\",\"start\":\"0.3,0.5\",\"end\":\"0.7,0.5\"}",
            json,
        )
    }
}
