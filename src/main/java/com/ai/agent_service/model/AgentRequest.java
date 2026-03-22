package com.ai.agent_service.model;

/**
 * Input to POST /agent.
 *
 * Example:
 * {
 *   "task": "What is the current time? Tell me in a friendly way."
 * }
 */
public record AgentRequest(String task) {}
