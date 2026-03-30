package com.ai.agent_service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AIRequestEvent {

    private String requestId;
    private String correlationId;
    private String userId;
    private String prompt;
    private String agentType;
    private Map<String, Object> context = new HashMap<>();
    private Instant createdAt;
    private int maxTokenBudget;

    public AIRequestEvent() {}

    // getters + setters for all fields
}