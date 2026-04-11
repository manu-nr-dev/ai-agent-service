package com.ai.agent_service.model;

@SuppressWarnings("preview")
public final class AgentContext {
    public static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> USER_ID = ScopedValue.newInstance();
    public static final ScopedValue<Double> COST_BUDGET = ScopedValue.newInstance();
    private AgentContext() {}
}