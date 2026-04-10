package com.ai.agent_service.evaluator;

import com.ai.agent_service.kafka.AIEventProducer;
import com.ai.agent_service.model.AIRequestEvent;
import com.ai.agent_service.model.*;
import com.ai.agent_service.orchestrator.AgentOrchestrator;
import com.ai.agent_service.model.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Day 40: Agent evaluation suite.
 *
 * Tests all 5 failure modes. Run via GET /agent/eval.
 * Each test case has a task, an expected behaviour, and a pass/fail check.
 *
 * This is not a unit test — it calls the real agent with real LLM.
 * Think of it as a smoke test for agent correctness.
 */
@Component
public class AgentEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AgentEvaluator.class);

    private final AgentOrchestrator orchestrator;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public AgentEvaluator(AgentOrchestrator orchestrator, JdbcTemplate jdbc, KafkaTemplate<String, Object> kafkaTemplate, AIEventProducer producer) {
        this.orchestrator = orchestrator;
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.producer = producer;
    }

    private final AIEventProducer producer;

    private record AsyncResult(String requestId, String status) {}

    public EvalReport runAll() {
        List<EvalResult> results = new ArrayList<>();

        results.add(evalIterationCapHolds());
        results.add(evalCorrectToolSelection());
        results.add(evalInvalidToolHandled());
        results.add(evalWeatherToolWorks());
        results.add(evalDBToolWorks());
        results.add(evalAsyncSuccess());
        results.add(evalAsyncBudgetExceeded());
        results.add(evalAsyncDuplicate());
        results.add(evalAsyncToolFail());

        long passed = results.stream().filter(EvalResult::passed).count();
        log.info("Eval complete: {}/{} passed", passed, results.size());
        return new EvalReport(results, (int) passed, results.size());
    }

    // ── Case 1: Iteration cap holds ───────────────────────────────────────────
    // Give the agent a task that cannot be completed with available tools.
    // It should hit the iteration cap, not loop forever.
    private EvalResult evalIterationCapHolds() {
        String task = "Find the population of every country in the world and sum them up.";
        log.info("Eval 1: iteration cap");
        try {
            AgentResponse r = orchestrator.run(task);
            boolean passed = switch (r) {
                case SuccessResponse(var answer, var iterationsUsed, var maxIterations,
                                     var toolsInvoked, var durationMs, var totalTokensUsed)
                        -> iterationsUsed <= maxIterations;
                case IterationLimitResponse(var maxIter, var toolsInvoked,
                                            var durationMs, var totalTokensUsed) -> true;
                default -> false;
            };
            return new EvalResult("iteration_cap_holds", task,
                    "iterationsUsed <= maxIterations", r.toString(), passed);
        } catch (Exception e) {
            return new EvalResult("iteration_cap_holds", task, "no exception", e.getMessage(), false);
        }
    }

    private EvalResult evalCorrectToolSelection() {
        String task = "What is the weather in Mumbai?";
        log.info("Eval 2: correct tool selection");
        try {
            AgentResponse r = orchestrator.run(task);
            boolean passed = switch (r) {
                case SuccessResponse(var answer, var iterationsUsed, var maxIterations,
                                     var toolsInvoked, var durationMs, var totalTokensUsed)
                        -> toolsInvoked.contains("get_weather");
                default -> false;
            };
            return new EvalResult("correct_tool_selection", task,
                    "toolsInvoked contains 'get_weather'", r.toString(), passed);
        } catch (Exception e) {
            return new EvalResult("correct_tool_selection", task, "no exception", e.getMessage(), false);
        }
    }

    private EvalResult evalInvalidToolHandled() {
        String task = "What is the current stock price of TCS?";
        log.info("Eval 3: invalid tool handled");
        try {
            AgentResponse r = orchestrator.run(task);
            boolean passed = switch (r) {
                case SuccessResponse(var answer, var iterationsUsed, var maxIterations,
                                     var toolsInvoked, var durationMs, var totalTokensUsed)
                        -> answer != null && !answer.isBlank();
                case IterationLimitResponse(var maxIter, var toolsInvoked,
                                            var durationMs, var totalTokensUsed) -> true;
                default -> false;
            };
            return new EvalResult("invalid_tool_handled", task,
                    "agent returns non-empty answer without crashing", r.toString(), passed);
        } catch (Exception e) {
            return new EvalResult("invalid_tool_handled", task, "no exception", e.getMessage(), false);
        }
    }

    private EvalResult evalWeatherToolWorks() {
        String task = "What is the weather in Bengaluru right now?";
        log.info("Eval 4: weather tool");
        try {
            AgentResponse r = orchestrator.run(task);
            boolean passed = switch (r) {
                case SuccessResponse(var answer, var iterationsUsed, var maxIterations,
                                     var toolsInvoked, var durationMs, var totalTokensUsed)
                        -> toolsInvoked.contains("get_weather")
                        && answer != null && answer.length() > 10;
                default -> false;
            };
            return new EvalResult("weather_tool_works", task,
                    "get_weather called, answer non-empty, no limit hit", r.toString(), passed);
        } catch (Exception e) {
            return new EvalResult("weather_tool_works", task, "no exception", e.getMessage(), false);
        }
    }

    private EvalResult evalDBToolWorks() {
        String task = "List all Electronics products from the database.";
        log.info("Eval 5: DB tool");
        try {
            AgentResponse r = orchestrator.run(task);
            boolean passed = switch (r) {
                case SuccessResponse(var answer, var iterationsUsed, var maxIterations,
                                     var toolsInvoked, var durationMs, var totalTokensUsed)
                        -> toolsInvoked.contains("lookup_products")
                        && answer != null && answer.length() > 10;
                default -> false;
            };
            return new EvalResult("db_tool_works", task,
                    "lookup_products called, answer non-empty, no limit hit", r.toString(), passed);
        } catch (Exception e) {
            return new EvalResult("db_tool_works", task, "no exception", e.getMessage(), false);
        }
    }

    // ── Records ───────────────────────────────────────────────────────────────
    public record EvalResult(
            String name,
            String task,
            String expected,
            String actual,
            boolean passed
    ) {}

    public record EvalReport(
            List<EvalResult> results,
            int passed,
            int total
    ) {
        public String summary() {
            return passed + "/" + total + " passed";
        }
    }

    // ── Case 6: Async success ─────────────────────────────────────────────────
    private EvalResult evalAsyncSuccess() {
        String task = "What products are available?";
        log.info("Eval 6: async success");
        try {
            String requestId = producer.publish("eval-user", task, "REACT", 5000);
            AsyncResult result = pollForResult(requestId, 30);
            boolean passed = result != null && "SUCCESS".equals(result.status());
            return new EvalResult("async_success", task,
                    "status=SUCCESS",
                    result != null ? "status=" + result.status() : "timeout",
                    passed);
        } catch (Exception e) {
            return new EvalResult("async_success", task, "status=SUCCESS", e.getMessage(), false);
        }
    }

    // ── Case 7: Budget exceeded ───────────────────────────────────────────────
    private EvalResult evalAsyncBudgetExceeded() {
        String task = "List every product in extreme detail with full descriptions.";
        log.info("Eval 7: async budget exceeded");
        try {
            String requestId = producer.publish("eval-user", task, "REACT", 100); // tiny budget
            AsyncResult result = pollForResult(requestId, 30);
            boolean passed = result != null && "BUDGET_EXCEEDED".equals(result.status());
            return new EvalResult("async_budget_exceeded", task,
                    "status=BUDGET_EXCEEDED",
                    result != null ? "status=" + result.status() : "timeout",
                    passed);
        } catch (Exception e) {
            return new EvalResult("async_budget_exceeded", task, "status=BUDGET_EXCEEDED", e.getMessage(), false);
        }
    }

    // ── Case 8: Duplicate request ─────────────────────────────────────────────
    private EvalResult evalAsyncDuplicate() {
        String task = "What is the current time?";
        log.info("Eval 8: async duplicate");
        try {
            String requestId = producer.publish("eval-user", task, "REACT", 5000);
            pollForResult(requestId, 30); // wait for first to complete
            // publish same requestId manually
            AIRequestEvent duplicate = new AIRequestEvent();
            duplicate.setRequestId(requestId); // same ID
            duplicate.setCorrelationId(UUID.randomUUID().toString());
            duplicate.setUserId("eval-user");
            duplicate.setPrompt(task);
            duplicate.setAgentType("REACT");
            duplicate.setMaxTokenBudget(5000);
            duplicate.setCreatedAt(Instant.now());
            kafkaTemplate.send("ai.requests", "eval-user", duplicate);
            AsyncResult result = pollForResult(requestId, 15);
            boolean passed = result != null && "DUPLICATE".equals(result.status());
            return new EvalResult("async_duplicate", task,
                    "status=DUPLICATE",
                    result != null ? "status=" + result.status() : "timeout",
                    passed);
        } catch (Exception e) {
            return new EvalResult("async_duplicate", task, "status=DUPLICATE", e.getMessage(), false);
        }
    }

    // ── Case 9: Tool arg validation fails ─────────────────────────────────────
    private EvalResult evalAsyncToolFail() {
        String task = "Get weather for"; // missing city
        log.info("Eval 9: async tool fail");
        try {
            String requestId = producer.publish("eval-user", task, "REACT", 5000);
            AsyncResult result = pollForResult(requestId, 30);
            boolean passed = result != null &&
                    ("FAILED".equals(result.status()) || "SUCCESS".equals(result.status()));
            return new EvalResult("async_tool_fail", task,
                    "FAILED or graceful SUCCESS",
                    result != null ? "status=" + result.status() : "timeout",
                    passed);
        } catch (Exception e) {
            return new EvalResult("async_tool_fail", task, "no exception", e.getMessage(), false);
        }
    }

    // ── Poll helper ───────────────────────────────────────────────────────────
    private AsyncResult pollForResult(String requestId, int timeoutSeconds)
            throws InterruptedException {
        int attempts = timeoutSeconds / 2;
        for (int i = 0; i < attempts; i++) {
            Thread.sleep(2000);
            List<String> rows = jdbc.query(
                    "SELECT status FROM agent_results WHERE request_id = ?",
                    (rs, n) -> rs.getString("status"),
                    requestId
            );
            if (!rows.isEmpty()) return new AsyncResult(requestId,rows.get(0));
        }
        return null;
    }
}
