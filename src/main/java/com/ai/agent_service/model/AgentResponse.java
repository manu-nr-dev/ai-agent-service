package com.ai.agent_service.model;

import java.util.List;

/**
 * Response from POST /agent.
 *
 * Exposes internal loop state so you can see what the agent did.
 * On Day 39+ you can slim this down for production.
 */
public record AgentResponse(
        String answer,              // The final answer the agent produced
        int iterationsUsed,         // How many ReAct iterations it took
        int maxIterations,          // The hard cap that was set
        List<String> toolsInvoked,  // Which tools were called, in order
        boolean hitIterationLimit,  // true if agent stopped because cap was reached
        long durationMs,             // Total wall time for the agent run
        int totalTokensUsed
) {}
