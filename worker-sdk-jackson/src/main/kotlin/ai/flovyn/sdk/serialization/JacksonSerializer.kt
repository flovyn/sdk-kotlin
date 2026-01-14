package ai.flovyn.sdk.serialization

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Jackson-based JSON serializer.
 *
 * Provides serialization/deserialization using Jackson with Kotlin and Java Time support.
 */
class JacksonSerializer(
    private val objectMapper: ObjectMapper = createDefaultObjectMapper()
) : JsonSerializer {

    override fun serialize(value: Any?): ByteArray {
        if (value == null) {
            return byteArrayOf()
        }
        return objectMapper.writeValueAsBytes(value)
    }

    override fun <T> deserialize(bytes: ByteArray, type: Class<T>): T {
        if (bytes.isEmpty()) {
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
        return objectMapper.readValue(bytes, type)
    }

    override fun deserializeToMap(bytes: ByteArray): Map<String, Any?> {
        if (bytes.isEmpty()) {
            return emptyMap()
        }
        return objectMapper.readValue(bytes)
    }

    companion object {
        /**
         * Create a default ObjectMapper with Kotlin and Java Time support.
         */
        fun createDefaultObjectMapper(): ObjectMapper {
            return ObjectMapper().apply {
                registerModule(KotlinModule.Builder().build())
                registerModule(JavaTimeModule())
                configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            }
        }
    }
}
