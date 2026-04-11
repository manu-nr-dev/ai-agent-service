package com.ai.agent_service.kafka;

import com.ai.agent_service.model.*;
import com.ai.agent_service.orchestrator.AgentOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Component
public class AIEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AIEventConsumer.class);

    private final AgentOrchestrator orchestrator;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JdbcTemplate jdbc;
    private ExecutorService virtualThreadExecutor;

    public AIEventConsumer(ExecutorService virtualThreadExecutor, JdbcTemplate jdbc, KafkaTemplate<String, Object> kafkaTemplate, AgentOrchestrator orchestrator) {
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.orchestrator = orchestrator;
    }

    @KafkaListener(topics = "ai.requests", containerFactory = "kafkaListenerContainerFactory")
    public void consume(AIRequestEvent event, Acknowledgment ack) throws Exception {
        AIResultEvent result = new AIResultEvent();
        result.setRequestId(event.getRequestId());
        result.setCorrelationId(event.getCorrelationId());
        result.setUserId(event.getUserId());
        result.setProcessedAt(Instant.now());

        if (isAlreadyProcessed(event.getRequestId())) {
            result.setStatus(ResultStatus.DUPLICATE);
            kafkaTemplate.send("ai.results", event.getUserId(), result);
            ack.acknowledge();
            return;
        }

        markProcessed(event.getRequestId());

        ScopedValue.where(AgentContext.REQUEST_ID, event.getRequestId())
                .where(AgentContext.USER_ID, event.getUserId())
                .where(AgentContext.COST_BUDGET, 0.05)
                .call(() -> {
                    long start = System.currentTimeMillis();
                    int maxRetries = 3;
                    int attempt = 0;

                    while (attempt <= maxRetries) {
                        try {
                            AgentResponse agentResponse = CompletableFuture
                                    .supplyAsync(() -> orchestrator.run(event.getPrompt()), virtualThreadExecutor)
                                    .join();

                            switch (agentResponse) {
                                case SuccessResponse(var answer, var iterationsUsed, var maxIterations,
                                                     var toolsInvoked, var durationMs, var totalTokensUsed) -> {
                                    result.setAnswer(answer);
                                    result.setIterationsUsed(iterationsUsed);
                                    result.setToolsInvoked(toolsInvoked);
                                    result.setTotalTokensUsed(totalTokensUsed);
                                    result.setDurationMs(durationMs);
                                    result.setStatus(ResultStatus.SUCCESS);
                                }
                                case BudgetExceededResponse(var totalTokensUsed, var maxCostTokens,
                                                            var toolsInvoked, var durationMs) -> {
                                    result.setStatus(ResultStatus.BUDGET_EXCEEDED);
                                    result.setErrorMessage("Budget exceeded: " + totalTokensUsed + "/" + maxCostTokens);
                                    result.setToolsInvoked(toolsInvoked);
                                    result.setTotalTokensUsed(totalTokensUsed);
                                }
                                case ErrorResponse(var message, var toolsInvoked, var durationMs, var totalTokensUsed) -> {
                                    result.setStatus(ResultStatus.FAILED);
                                    result.setErrorMessage(message);
                                    result.setToolsInvoked(toolsInvoked);
                                }
                                case IterationLimitResponse(var maxIterations, var toolsInvoked,
                                                            var durationMs, var totalTokensUsed) -> {
                                    result.setStatus(ResultStatus.ITERATION_LIMIT);
                                    result.setErrorMessage("Hit iteration limit: " + maxIterations);
                                    result.setToolsInvoked(toolsInvoked);
                                }
                            }

                            result.setProcessingMs(System.currentTimeMillis() - start);
                            kafkaTemplate.send("ai.results", event.getUserId(), result);
                            ack.acknowledge();
                            return null;

                        } catch (Exception e) {
                            attempt++;
                            if (attempt > maxRetries) {
                                log.error("All retries exhausted for requestId={}", event.getRequestId(), e);
                                sendToDlt(event, e.getMessage());
                                ack.acknowledge();
                                return null;
                            }
                            long backoff = (long) (1000 * Math.pow(2, attempt - 1));
                            log.warn("Retry {}/{} for requestId={}, backoff={}ms",
                                    attempt, maxRetries, event.getRequestId(), backoff);
                            Thread.sleep(backoff);
                        }
                    }
                    return null;
                });
    }
    private void sendToDlt(AIRequestEvent event, String reason) {
        jdbc.update(
                "INSERT INTO failed_ai_requests (request_id, prompt, failure_reason, failed_at) VALUES (?, ?, ?, NOW())",
                event.getRequestId(), event.getPrompt(), reason
        );
    }

    private boolean isAlreadyProcessed(String requestId) {
        List<String> rows = jdbc.query(
                "SELECT request_id FROM processed_requests WHERE request_id = ?",
                (rs, n) -> rs.getString("request_id"),
                requestId
        );
        return !rows.isEmpty();
    }

    private void markProcessed(String requestId) {
        jdbc.update(
                "INSERT INTO processed_requests (request_id, processed_at) VALUES (?, NOW()) ON CONFLICT DO NOTHING",
                requestId
        );
    }
}