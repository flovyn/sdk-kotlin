package ai.flovyn.sdk.serialization

/**
 * Interface for JSON serialization/deserialization.
 */
interface JsonSerializer {
    /**
     * Serialize a value to JSON bytes.
     */
    fun serialize(value: Any?): ByteArray

    /**
     * Deserialize JSON bytes to a value of the specified type.
     */
    fun <T> deserialize(bytes: ByteArray, type: Class<T>): T

    /**
     * Deserialize JSON bytes to a Map.
     */
    fun deserializeToMap(bytes: ByteArray): Map<String, Any?>
}
