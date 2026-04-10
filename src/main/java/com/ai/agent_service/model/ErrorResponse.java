package com.ai.agent_service.model;

import java.util.List;

public record ErrorResponse(
        String message,
        List<String> toolsInvoked,
        long durationMs,
        int totalTokensUsed
) implements AgentResponse {}
