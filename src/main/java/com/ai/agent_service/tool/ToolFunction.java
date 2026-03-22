package com.ai.agent_service.tool;

import java.util.Map;

/**
 * Contract every tool must implement.
 *
 * The LLM reads name() and description() to decide WHEN to call this tool.
 * Description quality is the #1 factor in agent reliability.
 * Bad description → wrong tool selection → wrong answer.
 *
 * On Day 55+ (MCP phase) this interface maps cleanly to an MCP tool definition.
 */
public interface ToolFunction {

    /**
     * Unique name. The LLM uses exactly this string in its tool_call JSON.
     * Convention: snake_case, e.g. "get_current_time", "search_database"
     */
    String name();

    /**
     * What this tool does and WHEN the agent should use it.
     * Write this for the LLM, not for a human developer.
     *
     * Good:  "Returns the current date and time in ISO-8601 format.
     *         Use this when the task requires knowing the current time."
     * Bad:   "Gets time"
     */
    String description();

    /**
     * Execute the tool with the given arguments.
     *
     * @param args  Key-value pairs the LLM provided
     * @return      The result string injected back as an observation
     * @throws Exception  Caller (AgentOrchestrator) handles tool failures
     */
    String execute(Map<String, String> args) throws Exception;
}
