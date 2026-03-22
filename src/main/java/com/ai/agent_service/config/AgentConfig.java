package com.ai.agent_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    /**
     * Spring AI ChatClient — wraps the Gemini API call.
     * The builder is auto-configured by spring-ai-openai-spring-boot-starter
     * using the properties in application.properties.
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
