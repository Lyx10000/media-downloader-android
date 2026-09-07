package com.local.multiplatformdownloader.core.network

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.firstValue(vararg names: String): Any? {
    for (name in names) {
        if (!has(name) || isNull(name)) continue
        return opt(name)
    }
    return null
}

internal fun JSONObject.firstObject(vararg names: String): JSONObject? =
    firstValue(*names) as? JSONObject

internal fun JSONObject.firstArray(vararg names: String): JSONArray? =
    firstValue(*names) as? JSONArray

internal fun JSONObject.firstString(vararg names: String): String =
    (firstValue(*names) as? String).orEmpty()

internal fun Any?.jsonNumber(): Int = when (this) {
    is Number -> toDouble().toInt()
    is String -> toDoubleOrNull()?.toInt() ?: 0
    else -> 0
}

internal fun Any?.jsonLong(): Long = when (this) {
    is Number -> toDouble().toLong()
    is String -> toDoubleOrNull()?.toLong() ?: 0L
    else -> 0L
}

internal fun JSONObject.keysInOrder(): List<String> = buildList {
    val iterator = keys()
    while (iterator.hasNext()) add(iterator.next())
}

internal fun JSONArray.values(): List<Any?> =
    (0 until length()).map { index -> opt(index).takeUnless { it === JSONObject.NULL } }

internal fun stableDistinct(values: Iterable<String>): List<String> {
    val seen = LinkedHashSet<String>()
    values.forEach { if (it.isNotBlank()) seen += it }
    return seen.toList()
}

internal fun responseShape(value: Any?, depth: Int = 0): Any {
    if (depth >= 6) return pythonTypeName(value)
    return when (value) {
        null, JSONObject.NULL -> "NoneType"
        is JSONObject -> JSONObject().apply {
            value.keysInOrder().forEach { key -> put(key, responseShape(value.opt(key), depth + 1)) }
        }
        is JSONArray -> JSONArray().apply {
            if (value.length() > 0) put(responseShape(value.opt(0), depth + 1))
        }
        else -> pythonTypeName(value)
    }
}

private fun pythonTypeName(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "NoneType"
    is JSONObject -> "dict"
    is JSONArray -> "list"
    is String -> "str"
    is Boolean -> "bool"
    is Float, is Double -> "float"
    is Number -> "int"
    else -> value::class.java.simpleName
}
