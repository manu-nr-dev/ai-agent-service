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

    @Value("${agent.max-iterations:10}")
    private int maxIterations;

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

    // ──────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Run the ReAct loop for the given task.
     * This is the only method the controller calls.
     */
    public AgentResponse run(String task) {
        long startMs = System.currentTimeMillis();

        log.info("Agent starting. Task: '{}'", task);

        // The conversation history — grows with each iteration
        // Format: alternating user/assistant turns
        List<String> history = new ArrayList<>();

        // Track which tools were called, in order
        List<String> toolsInvoked = new ArrayList<>();

        int iteration = 0;
        String finalAnswer = null;
        boolean hitLimit = false;

        // ── REACT LOOP ────────────────────────────────────────────────────────
        while (iteration < maxIterations) {
            iteration++;
            log.debug("── Iteration {} / {} ──────────────────────", iteration, maxIterations);

            // 1. Build the full prompt for this iteration
            String prompt = buildPrompt(task, history);

            // 2. Call the LLM
            String rawLlmResponse;
            try {
                rawLlmResponse = callLlm(prompt);
                log.debug("LLM raw response:\n{}", rawLlmResponse);
            } catch (Exception e) {
                log.error("LLM call failed on iteration {}: {}", iteration, e.getMessage());
                finalAnswer = "Agent failed: LLM call error — " + e.getMessage();
                break;
            }

            // 3. Parse the LLM's decision
            LlmDecision decision;
            try {
                decision = parseDecision(rawLlmResponse);
            } catch (Exception e) {
                log.warn("Failed to parse LLM response on iteration {}. Raw: {}", iteration, rawLlmResponse);
                // Inject an error into history and let the agent retry
                history.add("SYSTEM: Your last response could not be parsed. "
                        + "You MUST respond with valid JSON matching the required format.");
                continue;
            }

            log.debug("Thought: {}", decision.thought());

            // 4a. Final answer — we're done
            if (decision.isFinalAnswer()) {
                finalAnswer = decision.finalAnswer();
                log.info("Agent reached final answer after {} iteration(s).", iteration);
                break;
            }

            // 4b. Tool call — execute and inject observation
            if (decision.isToolCall()) {
                ToolCall tc = decision.toolCall();
                log.debug("Tool call: {} with args {}", tc.toolName(), tc.args());

                toolsInvoked.add(tc.toolName());

                String observation = toolRegistry.execute(tc.toolName(), tc.args());
                log.debug("Observation: {}", observation);

                // Build the turn for history
                // Assistant turn (what the LLM said)
                history.add("Assistant thought: " + decision.thought()
                        + "\nAction: " + tc.toolName()
                        + "\nAction input: " + tc.args());

                // User turn (the tool observation injected back)
                history.add("Observation: " + observation);
            }
        }
        // ── END REACT LOOP ────────────────────────────────────────────────────

        // If the loop ended without a final answer, we hit the iteration cap
        if (finalAnswer == null) {
            hitLimit = true;
            finalAnswer = "Agent reached the maximum iteration limit ("
                    + maxIterations + ") without producing a final answer. "
                    + "Tools invoked: " + toolsInvoked + ". "
                    + "Try simplifying the task or increasing the iteration limit.";
            log.warn("Agent hit iteration limit ({}) for task: '{}'", maxIterations, task);
        }

        long durationMs = System.currentTimeMillis() - startMs;
        log.info("Agent completed. Iterations: {}, Tools: {}, Duration: {}ms",
                iteration, toolsInvoked, durationMs);

        return new AgentResponse(
                finalAnswer,
                iteration,
                maxIterations,
                toolsInvoked,
                hitLimit,
                durationMs
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PRIVATE — PROMPT BUILDING
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Assembles the full prompt for one LLM call.
     *
     * Structure:
     *   [SYSTEM PROMPT — who you are, what tools exist, how to respond]
     *   [TASK]
     *   [HISTORY — all previous thoughts, actions, observations]
     *   [INSTRUCTION — what to do next]
     */
    private String buildPrompt(String task, List<String> history) {
        StringBuilder sb = new StringBuilder();

        // ── System prompt ──────────────────────────────────────────────────
        sb.append("""
                You are an AI agent that solves tasks step by step using available tools.
                
                AVAILABLE TOOLS:
                """);
        sb.append(toolRegistry.getToolDescriptions()).append("\n\n");

        sb.append("""
                RESPONSE FORMAT (strict JSON — no other format accepted):
                
                If you need to call a tool:
                {
                  "thought": "your reasoning about what to do next",
                  "action": "tool_name",
                  "action_input": {"key": "value"}
                }
                
                If you have enough information to answer:
                {
                  "thought": "your reasoning about why you can now answer",
                  "final_answer": "your complete answer to the user"
                }
                
                RULES:
                - Always include "thought" in every response.
                - Use a tool only when you actually need information you don't have.
                - Do not make up tool results. Always call the tool and use the observation.
                - When you have the information needed, give the final_answer immediately.
                - Respond ONLY with the JSON object. No preamble, no markdown, no explanation outside the JSON.
                
                """);

        // ── Task ──────────────────────────────────────────────────────────
        sb.append("TASK: ").append(task).append("\n\n");

        // ── History ───────────────────────────────────────────────────────
        if (!history.isEmpty()) {
            sb.append("PREVIOUS STEPS:\n");
            for (String turn : history) {
                sb.append(turn).append("\n\n");
            }
        }

        sb.append("Now respond with the next step as JSON:");

        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PRIVATE — LLM CALL
    // ──────────────────────────────────────────────────────────────────────────

    private String callLlm(String prompt) {
        return chatClient
                .prompt()
                .user(prompt)
                .call()
                .content();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PRIVATE — RESPONSE PARSING
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parses the LLM's JSON response into an LlmDecision.
     *
     * The LLM sometimes wraps its JSON in markdown code fences (```json ... ```).
     * We strip those before parsing.
     */
    private LlmDecision parseDecision(String raw) throws Exception {
        // Strip markdown code fences if present
        String json = raw.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "").trim();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("LLM response is not valid JSON: " + raw);
        }

        String thought = root.path("thought").asText("");

        // Final answer path
        if (root.has("final_answer") && !root.path("final_answer").asText().isBlank()) {
            return new LlmDecision(thought, null, root.path("final_answer").asText());
        }

        // Tool call path
        if (root.has("action") && !root.path("action").asText().isBlank()) {
            String toolName = root.path("action").asText();

            Map<String, String> args = new HashMap<>();
            JsonNode actionInput = root.path("action_input");
            if (actionInput.isObject()) {
                actionInput.fields().forEachRemaining(entry ->
                        args.put(entry.getKey(), entry.getValue().asText())
                );
            }

            return new LlmDecision(thought, new ToolCall(toolName, args), null);
        }

        // Neither final_answer nor action — the LLM didn't follow the format
        throw new IllegalArgumentException(
                "LLM response has neither 'final_answer' nor 'action': " + raw);
    }
}
