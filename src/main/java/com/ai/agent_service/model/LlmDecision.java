package com.ai.agent_service.model;

/**
 * The parsed output of one LLM reasoning step in the ReAct loop.
 *
 * The LLM can do one of two things in each iteration:
 *   1. Call a tool  → toolCall is populated, finalAnswer is null
 *   2. Give a final answer → finalAnswer is populated, toolCall is null
 *
 * The agent loop checks isFinalAnswer() to decide whether to continue.
 */
public record LlmDecision(
        String thought,         // The LLM's internal reasoning (always present)
        ToolCall toolCall,      // Non-null if the LLM wants to call a tool
        String finalAnswer      // Non-null if the LLM is done
) {

    public boolean isFinalAnswer() {
        return finalAnswer != null && !finalAnswer.isBlank();
    }

    public boolean isToolCall() {
        return toolCall != null;
    }
}
