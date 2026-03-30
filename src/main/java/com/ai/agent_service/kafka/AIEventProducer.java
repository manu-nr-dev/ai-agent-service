package com.ai.agent_service.kafka;

import com.ai.agent_service.model.AIRequestEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class AIEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public AIEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public String publish(String userId, String prompt, String agentType, int maxTokenBudget) {
        AIRequestEvent event = new AIRequestEvent();
        event.setRequestId(UUID.randomUUID().toString());
        event.setCorrelationId(UUID.randomUUID().toString());
        event.setUserId(userId);
        event.setPrompt(prompt);
        event.setAgentType(agentType);
        event.setMaxTokenBudget(maxTokenBudget);
        event.setCreatedAt(Instant.now());

        kafkaTemplate.send("ai.requests", userId, event); // partition by userId
        return event.getRequestId();
    }
}
