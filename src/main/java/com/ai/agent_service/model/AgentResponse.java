package com.ai.agent_service.model;

import java.util.List;

/**
 * Response from POST /agent.
 *
 * Exposes internal loop state so you can see what the agent did.
 * On Day 39+ you can slim this down for production.
 */
public sealed interface AgentResponse
        permits SuccessResponse, BudgetExceededResponse, ErrorResponse, IterationLimitResponse {}

