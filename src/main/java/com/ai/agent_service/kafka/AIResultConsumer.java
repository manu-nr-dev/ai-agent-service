package com.ai.agent_service.kafka;

import com.ai.agent_service.model.AIResultEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

@Component
public class AIResultConsumer {

    private final JdbcTemplate jdbc;

    public AIResultConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = "ai.results", containerFactory = "resultListenerContainerFactory")
    public void consume(AIResultEvent event) {
        jdbc.update("""
            INSERT INTO agent_results
              (request_id, user_id, result, status, error_message,
               tokens_used, iterations, processing_ms, created_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON CONFLICT (request_id) DO NOTHING
            """,
                event.getRequestId(), event.getUserId(), event.getResult(),
                event.getStatus().name(), event.getErrorMessage(),
                event.getTotalTokensUsed(), event.getIterationsUsed(),
                event.getProcessingMs(), Timestamp.from(event.getProcessedAt())
        );
    }
}
