package com.ai.agent_service.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds all registered tools.
 *
 * Spring auto-discovers every bean implementing ToolFunction and registers it.
 * AgentOrchestrator calls getToolDescriptions() to build the system prompt,
 * and execute(name, args) to run a tool after the LLM decides to call it.
 *
 * On Day 56 (MCP phase) this registry is replaced by LangChain4j's
 * McpToolProvider — the structure is the same, just the discovery mechanism changes.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    // Map of tool name → tool implementation
    private final Map<String, ToolFunction> tools;

    /**
     * Spring injects all ToolFunction beans here automatically.
     * Add a new tool: create a class, implement ToolFunction, annotate with @Component.
     * No changes needed here.
     */
    public ToolRegistry(List<ToolFunction> toolList) {
        this.tools = toolList.stream()
                .collect(Collectors.toMap(ToolFunction::name, Function.identity()));
        log.info("ToolRegistry initialised with {} tool(s): {}", tools.size(), tools.keySet());
    }

    /**
     * Returns a formatted string of all tools for injection into the system prompt.
     * The LLM reads this to know what tools are available and when to use them.
     */
    public String getToolDescriptions() {
        if (tools.isEmpty()) return "No tools available.";

        StringBuilder sb = new StringBuilder();
        tools.forEach((name, tool) ->
                sb.append("- ").append(name).append(": ").append(tool.description()).append("\n")
        );
        return sb.toString().trim();
    }

    /**
     * Execute a tool by name.
     *
     * @return tool result, or an error observation if the tool is unknown or throws
     */
    public String execute(String toolName, Map<String, String> args) {
        ToolFunction tool = tools.get(toolName);

        if (tool == null) {
            log.warn("Agent called unknown tool: '{}'", toolName);
            // Return an error observation — the agent can recover in the next iteration
            return "ERROR: Tool '" + toolName + "' does not exist. "
                    + "Available tools: " + tools.keySet();
        }

        try {
            String result = tool.execute(args);
            log.debug("Tool '{}' executed. Args: {}. Result: {}", toolName, args, result);
            return result;
        } catch (Exception e) {
            log.error("Tool '{}' threw an exception: {}", toolName, e.getMessage());
            return "ERROR: Tool '" + toolName + "' failed: " + e.getMessage();
        }
    }

    public Optional<ToolFunction> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Map<String, ToolFunction> getAllTools() {
        return Collections.unmodifiableMap(tools);
    }

    public int getToolCount() {
        return tools.size();
    }
}
