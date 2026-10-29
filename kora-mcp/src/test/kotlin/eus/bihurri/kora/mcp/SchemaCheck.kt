package eus.bihurri.kora.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A deliberately small validator for the schema keywords [JsonSchemas] emits (`type`, `properties`, `required`,
 * `items`, `additionalProperties`, `enum`, `anyOf`), so tests can check that real values conform to the schemas
 * the server advertises. Returns the list of violations; empty means the value conforms.
 */
fun violations(schema: JsonObject, value: JsonElement, path: String = "$"): List<String> {
    schema["anyOf"]?.let { options ->
        val attempts = options.jsonArray.map { violations(it.jsonObject, value, path) }
        return if (attempts.any { it.isEmpty() }) emptyList() else listOf("$path matches none of anyOf: $attempts")
    }
    val found = mutableListOf<String>()
    val types = when (val type = schema["type"]) {
        null -> null
        is JsonArray -> type.map { it.jsonPrimitive.content }
        else -> listOf(type.jsonPrimitive.content)
    }
    if (types != null && types.none { matches(it, value) }) return listOf("$path is $value, expected $types")
    schema["enum"]?.let { allowed ->
        if (value !in allowed.jsonArray) found += "$path is $value, not one of $allowed"
    }
    if (value is JsonObject) {
        schema["required"]?.jsonArray?.forEach { key ->
            if (key.jsonPrimitive.content !in value) found += "$path lacks '${key.jsonPrimitive.content}'"
        }
        val properties = schema["properties"]?.jsonObject
        val additional = schema["additionalProperties"]
        for ((key, child) in value) {
            val declared = properties?.get(key)?.jsonObject
            when {
                declared != null -> found += violations(declared, child, "$path.$key")
                additional is JsonPrimitive && additional.booleanOrNull == false -> found += "$path has undeclared '$key'"
                additional is JsonObject -> found += violations(additional, child, "$path.$key")
            }
        }
    }
    if (value is JsonArray) {
        schema["items"]?.jsonObject?.let { items -> value.forEachIndexed { i, e -> found += violations(items, e, "$path[$i]") } }
    }
    return found
}

private fun matches(type: String, value: JsonElement): Boolean = when (type) {
    "object" -> value is JsonObject
    "array" -> value is JsonArray
    "null" -> value is JsonNull
    "string" -> value is JsonPrimitive && value.isString
    "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
    "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
    "number" -> value is JsonPrimitive && !value.isString && value !is JsonNull
    else -> false
}
