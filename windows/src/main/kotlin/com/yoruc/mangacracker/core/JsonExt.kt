package com.yoruc.mangacracker.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Small null-safe readers for the sites' JSON.

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray get() = (this as? JsonArray) ?: JsonArray(emptyList())

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeIf { it !is JsonNull }

fun JsonElement?.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
fun JsonElement?.int(key: String): Int = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
fun JsonElement?.long(key: String): Long = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
fun JsonElement?.float(key: String): Float = (this[key] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: -1f
fun JsonElement?.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull == "true"
val JsonElement?.text: String get() = (this as? JsonPrimitive)?.contentOrNull ?: ""

/** Strings of an array that holds either plain strings or objects with [field]. */
fun JsonElement?.strings(field: String = "name"): List<String> = arr.mapNotNull {
    when (it) {
        is JsonPrimitive -> it.contentOrNull
        is JsonObject -> it.str(field).ifEmpty { null }
        else -> null
    }
}

/** ISO-8601 date -> epoch ms (0 when it does not parse). */
fun isoToMillis(s: String): Long = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
    .recoverCatching { java.time.Instant.parse(s).toEpochMilli() }
    .recoverCatching { java.time.LocalDate.parse(s.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
    .getOrDefault(0L)

fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")
