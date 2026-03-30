package com.ai.agent_service.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;


@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AIResultEvent {

    private String requestId;
    private String correlationId;
    private String userId;
    private ResultStatus status;
    private String errorMessage;
    private int totalTokensUsed;
    private int iterationsUsed;
    private Instant processedAt;
    private long processingMs;

    // replace String result with these
    private String answer;
    private List<String> toolsInvoked;
    private boolean hitIterationLimit;
    private long durationMs;
}