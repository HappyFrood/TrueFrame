package com.example.trueframe.data

import androidx.compose.ui.geometry.Offset
import com.example.trueframe.core.annotation.AnnotationShape
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

object OffsetSerializer : KSerializer<Offset> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Offset", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Offset) {
        encoder.encodeString("${value.x},${value.y}")
    }
    override fun deserialize(decoder: Decoder): Offset {
        val string = decoder.decodeString()
        val parts = string.split(",")
        return Offset(parts[0].toFloat(), parts[1].toFloat())
    }
}

@Serializable
sealed interface SerializableShape {
    @Serializable
    data class Line(
        @Serializable(with = OffsetSerializer::class) val start: Offset,
        @Serializable(with = OffsetSerializer::class) val end: Offset,
    ) : SerializableShape

    @Serializable
    data class Angle(
        @Serializable(with = OffsetSerializer::class) val start: Offset,
        @Serializable(with = OffsetSerializer::class) val center: Offset,
        @Serializable(with = OffsetSerializer::class) val end: Offset,
    ) : SerializableShape

    @Serializable
    data class Circle(
        @Serializable(with = OffsetSerializer::class) val center: Offset,
        val radius: Float,
    ) : SerializableShape
}

fun AnnotationShape.toSerializable(): SerializableShape = when (this) {
    is AnnotationShape.Line -> SerializableShape.Line(start, end)
    is AnnotationShape.Angle -> SerializableShape.Angle(start, center, end)
    is AnnotationShape.Circle -> SerializableShape.Circle(center, radius)
}

fun SerializableShape.toAnnotationShape(): AnnotationShape = when (this) {
    is SerializableShape.Line -> AnnotationShape.Line(start, end)
    is SerializableShape.Angle -> AnnotationShape.Angle(start, center, end)
    is SerializableShape.Circle -> AnnotationShape.Circle(center, radius)
}

object AnnotationJson {
    val format = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    fun serialize(shape: AnnotationShape): String {
        return format.encodeToString(SerializableShape.serializer(), shape.toSerializable())
    }

    fun deserialize(jsonString: String): AnnotationShape {
        return format.decodeFromString(SerializableShape.serializer(), jsonString).toAnnotationShape()
    }
}
