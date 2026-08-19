package com.inspiredandroid.kai.hotupdate

import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Bridges remotely-delivered tool definitions onto the compiled-in `Tool`
 * interface so the chat LLM sees them alongside normal tools — schema and
 * routing arrive over the air, execution lands on built-in executors only.
 */
internal class RemoteTool(
    private val definition: RemoteToolDefinition,
    private val executor: DynamicToolExecutor,
) : Tool {
    override val schema: ToolSchema = ToolSchema(
        name = definition.id,
        description = definition.description,
        parameters = definition.parameters.mapValues { (_, p) ->
            com.inspiredandroid.kai.network.tools.ParameterSchema(
                type = p.type,
                description = p.description,
                required = p.required,
                rawSchema = buildJsonObject {
                    put("type", JsonPrimitive(p.type))
                    put("description", JsonPrimitive(p.description))
                },
            )
        },
    )
    override val timeout: Duration =
        (definition.timeout_seconds?.coerceIn(5, 300) ?: 30).seconds

    override suspend fun execute(args: Map<String, Any>): Any = executor.execute(definition, args)
}
