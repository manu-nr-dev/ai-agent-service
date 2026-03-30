package com.ai.agent_service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;


@Data
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AIResultEvent {

    private String requestId;
    private String correlationId;
    private String userId;
    private String result;
    private ResultStatus status;
    private String errorMessage;
    private int totalTokensUsed;
    private int iterationsUsed;
    private Instant processedAt;
    private long processingMs;

    public AIResultEvent() {}

    // getters + setters for all fields
}