package com.ai.agent_service.model;

import java.util.List;

public record BudgetExceededResponse(
        int totalTokensUsed,
        int maxCostTokens,
        List<String> toolsInvoked,
        long durationMs
) implements AgentResponse {}
