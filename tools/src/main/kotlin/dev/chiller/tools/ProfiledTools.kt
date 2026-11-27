package dev.chiller.tools

import dev.chiller.contracts.*

/**
 * Offers only the tools a model profile allows. A call to any other tool is refused with a structured
 * error, so a model that remembers a hidden tool gets a recovery hint instead of an effect.
 */
class ProfiledTools(private val base: ToolExecutor, private val allowed: Set<String>) : ToolExecutor {
    override val specs: List<ToolSpec> = base.specs.filter { it.name in allowed }
    private val offered = specs.mapTo(mutableSetOf()) { it.name }

    override suspend fun execute(call: ToolCall, context: ToolContext): ToolResult =
        if (call.name in allowed) base.execute(call, context.copy(offeredTools = context.offeredTools?.intersect(offered) ?: offered))
        else ToolResult.failure("TOOL_NOT_IN_PROFILE", "Tool ${call.name} is not available for this model profile", recovery = "Use one of: ${specs.joinToString { it.name }}")
}
