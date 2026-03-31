package com.ai.agent_service.kafka;

import com.ai.agent_service.exception.BudgetExceededException;
import com.ai.agent_service.model.AIRequestEvent;
import com.ai.agent_service.model.AIResultEvent;
import com.ai.agent_service.model.AgentResponse;
import com.ai.agent_service.model.ResultStatus;
import com.ai.agent_service.orchestrator.AgentOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class AIEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AIEventConsumer.class);

    private final AgentOrchestrator orchestrator;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JdbcTemplate jdbc;

    public AIEventConsumer(AgentOrchestrator orchestrator,
                           KafkaTemplate<String, Object> kafkaTemplate,
                           JdbcTemplate jdbc) {
        this.orchestrator = orchestrator;
        this.kafkaTemplate = kafkaTemplate;
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = "ai.requests", containerFactory = "kafkaListenerContainerFactory")
    public void consume(AIRequestEvent event) throws InterruptedException {
        AIResultEvent result = new AIResultEvent();
        result.setRequestId(event.getRequestId());
        result.setCorrelationId(event.getCorrelationId());
        result.setUserId(event.getUserId());
        result.setProcessedAt(Instant.now());

        if (isAlreadyProcessed(event.getRequestId())) {
            result.setStatus(ResultStatus.DUPLICATE);
            kafkaTemplate.send("ai.results", event.getUserId(), result);
            return;
        }

        markProcessed(event.getRequestId());

        long start = System.currentTimeMillis();
        int maxRetries = 3;
        int attempt = 0;

        while (attempt <= maxRetries) {
            try {
                AgentResponse agentResponse = orchestrator.run(event.getPrompt());
                result.setAnswer(agentResponse.answer());
                result.setIterationsUsed(agentResponse.iterationsUsed());
                result.setToolsInvoked(agentResponse.toolsInvoked());
                result.setHitIterationLimit(agentResponse.hitIterationLimit());
                result.setDurationMs(agentResponse.durationMs());
                result.setTotalTokensUsed(agentResponse.totalTokensUsed());
                result.setStatus(ResultStatus.SUCCESS);
                result.setProcessingMs(System.currentTimeMillis() - start);
                kafkaTemplate.send("ai.results", event.getUserId(), result);
                return;
            } catch (BudgetExceededException e) {
                result.setStatus(ResultStatus.BUDGET_EXCEEDED);
                result.setErrorMessage(e.getMessage());
                result.setProcessingMs(System.currentTimeMillis() - start);
                kafkaTemplate.send("ai.results", event.getUserId(), result);
                return;
            } catch (Exception e) {
                attempt++;
                if (attempt > maxRetries) {
                    log.error("All retries exhausted for requestId={}", event.getRequestId(), e);
                    sendToDlt(event, e.getMessage());
                    return;
                }
                long backoff = (long) (1000 * Math.pow(2, attempt - 1)); // 1s, 2s, 4s
                log.warn("Retry {}/{} for requestId={}, backoff={}ms", attempt, maxRetries, event.getRequestId(), backoff);
                Thread.sleep(backoff);
            }
        }
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