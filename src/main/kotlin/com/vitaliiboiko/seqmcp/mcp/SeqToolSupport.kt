// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import com.vitaliiboiko.seqmcp.services.SeqApiException
import com.vitaliiboiko.seqmcp.services.SeqMcpBackend
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException

private val toolCategory = McpToolCategory(
    shortName = "seq",
    fullyQualifiedName = "com.vitaliiboiko.seqmcp",
    isExperimental = false,
    alwaysIncluded = false,
)

internal fun descriptor(
    name: String,
    description: String,
    inputSchema: McpToolSchema,
    outputSchema: McpToolSchema,
): McpToolDescriptor {
    return McpToolDescriptor(
        name = name,
        description = description,
        category = toolCategory,
        fullyQualifiedName = "${toolCategory.fullyQualifiedName}.$name",
        inputSchema = inputSchema,
        outputSchema = outputSchema,
    )
}

internal class JsonBackedTool(
    override val descriptor: McpToolDescriptor,
    private val invoke: suspend (JsonObject) -> McpToolCallResult,
) : McpTool {
    override suspend fun call(args: JsonObject): McpToolCallResult {
        return runCatching {
            invoke(args)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            McpToolCallResult.Companion.error(
                error.message ?: "Unexpected Seq MCP error",
                buildJsonObject {
                    put("error", JsonPrimitive(error.message ?: "Unexpected Seq MCP error"))
                    if (error is SeqApiException) {
                        error.errorCode?.let { put("code", JsonPrimitive(it)) }
                        error.statusCode?.let { put("statusCode", JsonPrimitive(it)) }
                    }
                },
            )
        }
    }
}

internal fun inputSchema(
    required: Set<String> = emptySet(),
    properties: Map<String, JsonElement>,
): McpToolSchema {
    val propertiesWithProjectPath = linkedMapOf(
        "projectPath" to projectPathInputSchema(),
    ).apply {
        putAll(properties)
    }
    return buildSchema(*propertiesWithProjectPath.entries.map { it.key to it.value }.toTypedArray(), required = required)
}

internal const val WORKSPACE_PARAMETER_DESCRIPTION =
    "Optional workspace credential key. If it matches a configured workspace API key override, that credential is used; otherwise the default Seq API key is used."

internal const val WORKSPACE_OUTPUT_DESCRIPTION = "Workspace credential key echoed back in the response."

internal const val PROJECT_PATH_PARAMETER_DESCRIPTION =
    "Optional absolute IDE project path used by JetBrains MCP clients to resolve the target project before dispatch. Seq MCP also uses it to prefer that project when multiple projects are open."

internal fun buildSchema(
    vararg properties: Pair<String, JsonElement>,
    required: Set<String> = emptySet(),
): McpToolSchema {
    return McpToolSchema.Companion.ofPropertiesMap(
        properties = linkedMapOf(*properties),
        requiredProperties = required,
        definitions = emptyMap(),
        definitionsPath = McpToolSchema.DEFAULT_DEFINITIONS_PATH,
    )
}

internal fun stringSchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
    }
}

internal fun nullableStringSchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", buildJsonArray {
            add(JsonPrimitive("string"))
            add(JsonPrimitive("null"))
        })
        put("description", JsonPrimitive(description))
    }
}

internal fun workspaceInputSchema(): JsonElement = stringSchema(WORKSPACE_PARAMETER_DESCRIPTION)

internal fun workspaceOutputSchema(): JsonElement = nullableStringSchema(WORKSPACE_OUTPUT_DESCRIPTION)

private fun projectPathInputSchema(): JsonElement = stringSchema(PROJECT_PATH_PARAMETER_DESCRIPTION)

internal fun intSchema(
    description: String,
    defaultValue: Int? = null,
    minimum: Int? = null,
    maximum: Int? = null,
): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(description))
        defaultValue?.let { put("default", JsonPrimitive(it)) }
        minimum?.let { put("minimum", JsonPrimitive(it)) }
        maximum?.let { put("maximum", JsonPrimitive(it)) }
    }
}

internal fun booleanSchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(description))
    }
}

internal fun arraySchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("description", JsonPrimitive(description))
        put(
            "items",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
            },
        )
    }
}

internal fun stringArraySchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("description", JsonPrimitive(description))
        put(
            "items",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
            },
        )
    }
}

internal fun objectSchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("description", JsonPrimitive(description))
    }
}

internal fun success(text: String, structured: JsonObject): McpToolCallResult {
    return McpToolCallResult.Companion.text(text, structured)
}

internal fun failure(text: String, structured: JsonObject): McpToolCallResult {
    return McpToolCallResult.Companion.error(text, structured)
}

internal fun requiredString(arguments: JsonObject, name: String): String {
    return arguments[name]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Missing required parameter: $name")
}

internal fun optionalString(arguments: JsonObject, name: String): String? {
    return arguments[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}

internal fun optionalInt(arguments: JsonObject, name: String): Int? {
    return arguments[name]?.jsonPrimitive?.intOrNull
}

internal fun optionalBoolean(arguments: JsonObject, name: String): Boolean? {
    return arguments[name]?.jsonPrimitive?.booleanOrNull
        ?: arguments[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
}

internal suspend fun SeqMcpBackend.validateSignalIfNeeded(signalId: String?, workspace: String?) {
    if (signalId == null) {
        return
    }

    val signalIds = listSignals(workspace).mapNotNull { signal ->
        signal.jsonObject["Id"]?.jsonPrimitive?.contentOrNull
    }.toSet()

    require(signalId in signalIds) {
        "Unknown signalId `$signalId`. Use SignalList to inspect available signals first."
    }
}

internal fun optionalInstant(arguments: JsonObject, name: String): Instant? {
    val value = optionalString(arguments, name) ?: return null
    return try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("$name must be an ISO-8601 UTC timestamp like 2026-04-18T10:15:30Z")
    }
}

internal fun JsonObjectBuilder.putNullable(name: String, value: String?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

internal fun JsonObject.stringValue(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.booleanValue(name: String): Boolean {
    return this[name]?.jsonPrimitive?.booleanOrNull
        ?: this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        ?: false
}

internal fun JsonElement.toSignalExpressionString(): String? {
    return when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonObject -> when {
            this["SignalId"] != null -> this["SignalId"]?.jsonPrimitive?.contentOrNull
            else -> this.toString()
        }
        else -> toString()
    }
}
