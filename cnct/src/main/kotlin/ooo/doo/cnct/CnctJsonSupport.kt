package ooo.doo.cnct

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.OffsetDateTime

/**
 * How every model in this SDK is read off the wire.
 *
 * Forgiving in exactly the ways the Flutter SDK's hand-written `fromJson` factories are, so the two
 * agree about what a response means:
 *
 * - **A field this SDK does not know is ignored.** The platform adds fields without asking, and an
 *   app built today must not crash on the response it gets next year.
 * - **A missing or null field takes its default.** `name` absent is `""`, not an exception.
 * - **An enum value this SDK has not met becomes the enum's catch-all** — `UNKNOWN`, `OTHER` —
 *   rather than taking the whole message down with it.
 * - **A number where a string was expected is read as its text**, the way Dart's `'${json[k]}'`
 *   reads it.
 */
internal val CnctJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/**
 * Decode a model, and turn a response that does not fit into the SDK's own error rather than a
 * serialization stack trace. A body that parses as JSON but is the wrong shape is still the server
 * saying something this client cannot read, which is what [CnctErrorCode.BAD_RESPONSE] means.
 */
internal fun <T> decodeModel(deserializer: DeserializationStrategy<T>, element: JsonElement): T = try {
    CnctJson.decodeFromJsonElement(deserializer, element)
} catch (error: IllegalArgumentException) {
    // SerializationException is an IllegalArgumentException, and so is what the tree decoder throws
    // for a number that is not one.
    throw CnctException(
        "The server answered in a shape this SDK cannot read: ${error.message}",
        code = CnctErrorCode.BAD_RESPONSE,
        cause = error,
    )
}

/** The server's answer as an object, or a [CnctErrorCode.BAD_RESPONSE] saying what came instead. */
internal fun asObject(value: JsonElement): JsonObject = value as? JsonObject
    ?: throw CnctException(
        "Expected an object from the server and got ${describe(value)}.",
        code = CnctErrorCode.BAD_RESPONSE,
    )

private fun describe(value: JsonElement): String = when (value) {
    JsonNull -> "nothing"
    is JsonArray -> "a list"
    is JsonPrimitive -> if (value.isString) "a string" else "a ${value.content}"
    is JsonObject -> "an object"
}

/** A string field, or null when it is missing or is not a string — Dart's `json[key] as String?`. */
internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The objects in a list field. Anything in it that is not an object is skipped, as the Flutter SDK's
 * `whereType<Map>()` skips it: one malformed entry should cost that entry, not the list.
 */
internal fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

/**
 * Plain Kotlin values as JSON, for the arguments an app hands in — a ticket's `fields`, a raw tool
 * call. Strings, numbers, booleans, null, maps, lists, arrays and [JsonElement]s go through; anything
 * else is refused here, with its type named, rather than being sent as its `toString()`.
 */
internal fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Enum<*> -> JsonPrimitive(value.name)
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toJsonElement(item) })
    is Iterable<*> -> JsonArray(value.map(::toJsonElement))
    is Array<*> -> JsonArray(value.map(::toJsonElement))
    else -> throw CnctException(
        "Cannot send a ${value::class.java.simpleName} as JSON. Pass strings, numbers, booleans, " +
            "lists and maps.",
        code = CnctErrorCode.INVALID,
    )
}

internal fun toJsonObject(values: Map<String, Any?>): JsonObject =
    values as? JsonObject ?: JsonObject(values.mapValues { (_, item) -> toJsonElement(item) })

/**
 * An ISO-8601 instant, or null for anything that is not one. The platform sends `toISOString()`
 * (always `Z`), but an offset is accepted too, because the JVM's [Instant.parse] only learned to read
 * one in Java 12.
 */
internal fun parseInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
        Instant.parse(raw)
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (_: Exception) {
            null
        }
    }
}

/** A string, lower-cased on the way in. For values the platform documents as lower-case. */
internal object LowercaseStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = String.serializer().descriptor

    override fun deserialize(decoder: Decoder): String = decoder.decodeString().lowercase()

    override fun serialize(encoder: Encoder, value: String): Unit = encoder.encodeString(value)
}

/**
 * A list of strings where the server may send numbers or booleans in it — `mustBeOneOf: [1, 2, 3]` is
 * three answers, not a malformed response. Each item is read as its text; a value that is not a list
 * at all is read as no items.
 */
internal object StringListSerializer : KSerializer<List<String>> {
    private val delegate = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<String> {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return delegate.deserialize(decoder)
        return (element as? JsonArray)
            ?.map { item -> (item as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: item.toString() }
            .orEmpty()
    }

    override fun serialize(encoder: Encoder, value: List<String>): Unit = delegate.serialize(encoder, value)
}

/**
 * A timestamp that is never missing. The platform stamps every message and every conversation, and a
 * row with a garbled one is still worth showing — so, as in the Flutter SDK, an unreadable value is
 * read as "now" rather than failing the row.
 */
internal object InstantOrNowSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ooo.doo.cnct.Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant {
        val raw = (decoder as? JsonDecoder)?.decodeJsonElement()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: if (decoder is JsonDecoder) null else decoder.decodeString()
        return parseInstant(raw) ?: Instant.now()
    }

    override fun serialize(encoder: Encoder, value: Instant): Unit = encoder.encodeString(value.toString())
}
