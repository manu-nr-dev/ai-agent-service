package com.ai.agent_service.orchestrator;

import com.ai.agent_service.model.ToolCall;
import com.ai.agent_service.model.AgentResponse;
import com.ai.agent_service.model.LlmDecision;
import com.ai.agent_service.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The heart of the agent: the ReAct loop.
 *
 * ReAct = Reason + Act. Pattern from Yao et al. 2022.
 * Every major agent framework (LangChain, LangChain4j, Spring AI Advisors,
 * OpenClaw, NemoClaw) implements a variant of this loop.
 *
 * Loop structure:
 *   while (iterations < MAX) {
 *     1. Build prompt: system prompt + conversation history so far
 *     2. Call LLM → get a decision (tool call OR final answer)
 *     3. If final answer → return
 *     4. If tool call → execute tool → inject observation into history → loop
 *   }
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * WHY THE HARD CAP MATTERS
 * ──────────────────────────────────────────────────────────────────────────────
 * Without a hard cap, a confused or looping agent will burn your entire
 * API quota and never stop. This is failure mode #1 in production agents.
 * Default: 10 iterations. Never go above 20.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * PROMPT FORMAT
 * ──────────────────────────────────────────────────────────────────────────────
 * We instruct the LLM to respond in a strict JSON format every time:
 * {
 *   "thought": "my reasoning...",
 *   "action": "tool_name",          // present only if calling a tool
 *   "action_input": {"key": "val"}, // present only if calling a tool
 *   "final_answer": "answer..."     // present only if done
 * }
 *
 * This makes parsing deterministic and avoids fragile regex scraping.
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final int SUMMARISE_EVERY = 5;
    private static final int CHARS_PER_TOKEN = 4;

    @Value("${agent.max-iterations:10}")
    private int maxIterations;

    @Value("${agent.max-cost-tokens:5000}")
    private int maxCostTokens;

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public AgentOrchestrator(ChatClient.Builder chatClientBuilder,
                             ToolRegistry toolRegistry,
                             ObjectMapper objectMapper) {
        this.chatClient   = chatClientBuilder.build();
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    public AgentResponse run(String task) {
        long startMs = System.currentTimeMillis();
        log.info("Agent starting. Task: '{}'", task);

        List<String> history      = new ArrayList<>();
        List<String> toolsInvoked = new ArrayList<>();
        int    iteration   = 0;
        int    totalTokens = 0;
        String finalAnswer = null;
        boolean hitLimit   = false;

        while (iteration < maxIterations) {
            iteration++;
            log.debug("-- Iteration {} / {} --", iteration, maxIterations);

            // Guardrail 1: Summarise history every N iterations
            if (iteration > 1 && (iteration - 1) % SUMMARISE_EVERY == 0) {
                log.debug("Summarising history at iteration {}", iteration);
                history =  summariseHistory(history, task);
            }

            String prompt = buildPrompt(task, history);

            // Guardrail 2: Cost budget check
            int promptTokens = estimateTokens(prompt);
            if (totalTokens + promptTokens > maxCostTokens) {
                hitLimit   = true;
                finalAnswer = "Agent stopped: cost budget exceeded ("
                        + totalTokens + " / " + maxCostTokens + " tokens). "
                        + "Tools invoked: " + toolsInvoked;
                log.warn("Cost budget exceeded. Tokens used: {}", totalTokens);
                break;
            }

            String rawResponse;
            try {
                rawResponse  = callLlm(prompt);
                totalTokens += estimateTokens(prompt) + estimateTokens(rawResponse);
                log.debug("Tokens this iteration: ~{}. Total: ~{}",
                        estimateTokens(rawResponse), totalTokens);
            } catch (Exception e) {
                log.error("LLM call failed: {}", e.getMessage());
                finalAnswer = "Agent failed: LLM error — " + e.getMessage();
                break;
            }

            LlmDecision decision;
            try {
                decision = parseDecision(rawResponse);
                log.debug("Thought: {}", decision.thought());
            } catch (Exception e) {
                log.warn("Parse failed on iteration {}. Injecting correction.", iteration);
                history.add("SYSTEM: Your last response was not valid JSON. Respond ONLY with the JSON format specified.");
                continue;
            }

            if (decision.isFinalAnswer()) {
                finalAnswer = decision.finalAnswer();
                log.info("Final answer after {} iteration(s). Tokens: ~{}", iteration, totalTokens);
                break;
            }

            if (decision.isToolCall()) {
                ToolCall tc = decision.toolCall();
                log.debug("Tool call: {} args: {}", tc.toolName(), tc.args());

                // Guardrail 3: Validate args before executing
                String validationError = validateToolArgs(tc);
                if (validationError != null) {
                    log.warn("Tool arg validation failed: {}", validationError);
                    history.add("Assistant thought: " + decision.thought() + "\nAction: " + tc.toolName());
                    history.add("Observation: VALIDATION ERROR — " + validationError + " Please retry with correct arguments.");
                    continue;
                }

                toolsInvoked.add(tc.toolName());
                String observation = toolRegistry.execute(tc.toolName(), tc.args());
                log.debug("Observation: {}", observation);

                history.add("Assistant thought: " + decision.thought()
                        + "\nAction: " + tc.toolName()
                        + "\nAction input: " + tc.args());
                history.add("Observation: " + observation);
            }
        }

        if (finalAnswer == null) {
            hitLimit    = true;
            finalAnswer = "Agent reached iteration limit (" + maxIterations + "). Tools: " + toolsInvoked;
            log.warn("Agent hit iteration limit for task: '{}'", task);
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("Agent done. Iterations: {}, Tokens: ~{}, Tools: {}, Duration: {}ms",
                iteration, totalTokens, toolsInvoked, durationMs);

        return new AgentResponse(finalAnswer, iteration, maxIterations, toolsInvoked, hitLimit, durationMs,totalTokens);
    }

    // ── Guardrail 1: History summarisation ───────────────────────────────────
    private List<String> summariseHistory(List<String> history, String task) {
        if (history.isEmpty()) return history;

        String prompt = """
                Summarise the progress of an AI agent working on this task: %s
                
                Steps so far:
                %s
                
                Write 3-5 sentences covering: tools called, what they returned, what is still needed.
                Be factual. Include all key data from observations.
                """.formatted(task, String.join("\n\n", history));

        try {
            String summary = callLlm(prompt);
            log.debug("History summarised. Was {} entries.", history.size());
            return new ArrayList<>(List.of("PROGRESS SUMMARY:\n" + summary));
        } catch (Exception e) {
            log.warn("Summarisation failed, keeping raw history: {}", e.getMessage());
            return history;
        }
    }

    // ── Guardrail 2: Token estimation ─────────────────────────────────────────
    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.length() / CHARS_PER_TOKEN;
    }

    // ── Guardrail 3: Tool arg validation ──────────────────────────────────────
    private String validateToolArgs(ToolCall tc) {
        if (toolRegistry.getTool(tc.toolName()).isEmpty()) {
            return "Tool '" + tc.toolName() + "' does not exist. Available: " + toolRegistry.getAllTools().keySet();
        }
        return switch (tc.toolName()) {
            case "get_weather" -> {
                if (!tc.args().containsKey("city") || tc.args().get("city").isBlank())
                    yield "Tool 'get_weather' requires arg 'city'. Example: {\"city\": \"Bengaluru\"}";
                yield null;
            }
            default -> null;
        };
    }

    // ── Prompt builder ────────────────────────────────────────────────────────
    private String buildPrompt(String task, List<String> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an AI agent that solves tasks step by step using available tools.\n\n");
        sb.append("AVAILABLE TOOLS:\n").append(toolRegistry.getToolDescriptions()).append("\n\n");
        sb.append("""
                RESPONSE FORMAT (strict JSON only):
                To call a tool: {"thought": "reasoning", "action": "tool_name", "action_input": {"key": "value"}}
                To give final answer: {"thought": "reasoning", "final_answer": "your answer"}
                RULES: Always include "thought". Never fabricate tool results. Respond ONLY with JSON.
                
                """);
        sb.append("TASK: ").append(task).append("\n\n");
        if (!history.isEmpty()) {
            sb.append("PREVIOUS STEPS:\n");
            history.forEach(h -> sb.append(h).append("\n\n"));
        }
        sb.append("Next step (JSON only):");
        return sb.toString();
    }

    // ── LLM call ──────────────────────────────────────────────────────────────
    private String callLlm(String prompt) {
        return chatClient.prompt().user(prompt).call().content();
    }

    // ── Response parser ───────────────────────────────────────────────────────
    private LlmDecision parseDecision(String raw) throws Exception {
        String json = raw.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "").trim();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not valid JSON: " + raw);
        }
        String thought = root.path("thought").asText("");
        if (root.has("final_answer") && !root.path("final_answer").asText().isBlank()) {
            return new LlmDecision(thought, null, root.path("final_answer").asText());
        }
        if (root.has("action") && !root.path("action").asText().isBlank()) {
            String toolName = root.path("action").asText();
            Map<String, String> args = new HashMap<>();
            JsonNode input = root.path("action_input");
            if (input.isObject()) {
                input.fields().forEachRemaining(e -> args.put(e.getKey(), e.getValue().asText()));
            }
            return new LlmDecision(thought, new ToolCall(toolName, args), null);
        }
        throw new IllegalArgumentException("No 'final_answer' or 'action' in: " + raw);
    }
}