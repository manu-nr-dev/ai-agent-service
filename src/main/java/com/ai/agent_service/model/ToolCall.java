package com.ai.agent_service.model;

import java.util.Map;

/**
 * Represents a tool call decision made by the LLM.
 *
 * The LLM returns this when it wants to invoke a tool instead of
 * giving a final answer. Your Java code executes the tool and injects
 * the result back as an observation.
 *
 * This is the boundary between "LLM decides" and "Java executes."
 */
public record ToolCall(
        String toolName,            // Must match a registered tool name exactly
        Map<String, String> args    // Arguments the LLM wants to pass to the tool
) {}
