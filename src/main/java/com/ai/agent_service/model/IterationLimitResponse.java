package com.ai.agent_service.model;

import java.util.List;

public record IterationLimitResponse(
        int maxIterations,
        List<String> toolsInvoked,
        long durationMs,
        int totalTokensUsed
) implements AgentResponse {}
