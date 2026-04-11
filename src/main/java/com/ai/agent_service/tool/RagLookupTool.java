package com.ai.agent_service.tool;

import com.ai.agent_service.model.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public class RagLookupTool implements ToolFunction {

    private static final Logger log = LoggerFactory.getLogger(RagLookupTool.class);
    private static final String RAG_URL = "http://localhost:8081/rag/ask";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String name() { return "rag_lookup"; }

    @Override
    public String description() {
        return "Search internal knowledge base for context on a topic. "
                + "Use when the question requires domain-specific or internal knowledge. and be descriptive cause rag uses semantic search if there is enough context it will be able to provide effective result"
                + "Args: 'query' (required) — the search query string.";
    }

    @Override
    public String execute(Map<String, String> args) throws Exception {
        String requestId = AgentContext.REQUEST_ID.orElse("unknown");
        String userId = AgentContext.USER_ID.orElse("unknown");
        log.debug("[requestId={}] [userId={}] rag_lookup invoked", requestId, userId);

        String query = args.get("query");
        if (query == null || query.isBlank()) {
            return "ERROR: 'query' argument is required for rag_lookup.";
        }
        query = args.get("query")+" and be concise no long answers";
        String body = "{\"query\":\"" + query.replace("\"", "\\\"") + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RAG_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            return "ERROR: RAG service returned status " + response.statusCode();
        }

        log.debug("RAG result for '{}': {}", query, response.body());
        return response.body().trim();
    }
}

