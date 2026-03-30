package com.ai.agent_service.kafka;

import com.ai.agent_service.exception.BudgetExceededException;
import com.ai.agent_service.model.AIRequestEvent;
import com.ai.agent_service.model.AIResultEvent;
import com.ai.agent_service.model.AgentResponse;
import com.ai.agent_service.model.ResultStatus;
import com.ai.agent_service.orchestrator.AgentOrchestrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class AIEventConsumer {

    private final AgentOrchestrator orchestrator;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final JdbcTemplate jdbc;

    public AIEventConsumer(AgentOrchestrator orchestrator, KafkaTemplate<String, Object> kafkaTemplate, JdbcTemplate jdbc) {
        this.orchestrator = orchestrator;
        this.kafkaTemplate = kafkaTemplate;
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = "ai.requests", containerFactory = "kafkaListenerContainerFactory")
    public void consume(AIRequestEvent event, Acknowledgment ack) {
        AIResultEvent result = new AIResultEvent();
        result.setRequestId(event.getRequestId());
        result.setCorrelationId(event.getCorrelationId());
        result.setUserId(event.getUserId());
        result.setProcessedAt(Instant.now());

        // Idempotency check
        if (isAlreadyProcessed(event.getRequestId())) {
            result.setStatus(ResultStatus.DUPLICATE);
            kafkaTemplate.send("ai.results", event.getUserId(), result);
            ack.acknowledge();
            return;
        }
// process...
        markProcessed(event.getRequestId());

        long start = System.currentTimeMillis();
        try {
            AgentResponse agentResponse = orchestrator.run(event.getPrompt());
            result.setAnswer(agentResponse.answer());
            result.setIterationsUsed(agentResponse.iterationsUsed());
            result.setToolsInvoked(agentResponse.toolsInvoked());
            result.setHitIterationLimit(agentResponse.hitIterationLimit());
            result.setDurationMs(agentResponse.durationMs());
            result.setStatus(ResultStatus.SUCCESS);
        } catch (BudgetExceededException e) {
            result.setStatus(ResultStatus.BUDGET_EXCEEDED);
            result.setErrorMessage(e.getMessage());
        } catch (Exception e) {
            result.setStatus(ResultStatus.FAILED);
            result.setErrorMessage(e.getMessage());
        } finally {
            result.setProcessingMs(System.currentTimeMillis() - start);
            kafkaTemplate.send("ai.results", event.getUserId(), result);
            ack.acknowledge();
        }
    }

    // Replace the in-memory check with:
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