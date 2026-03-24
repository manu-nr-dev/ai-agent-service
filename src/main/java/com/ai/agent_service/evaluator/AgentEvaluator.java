package com.ai.agent_service.evaluator;

import com.ai.agent_service.orchestrator.AgentOrchestrator;
import com.ai.agent_service.model.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


import java.util.ArrayList;
import java.util.List;

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

    public AgentEvaluator(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public EvalReport runAll() {
        List<EvalResult> results = new ArrayList<>();

        results.add(evalIterationCapHolds());
        results.add(evalCorrectToolSelection());
        results.add(evalInvalidToolHandled());
        results.add(evalWeatherToolWorks());
        results.add(evalDBToolWorks());

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
            // Pass: agent stopped (either hit limit or gave up gracefully)
            boolean passed = r.iterationsUsed() <= r.maxIterations();
            return new EvalResult("iteration_cap_holds", task,
                    "iterationsUsed <= maxIterations",
                    "iterationsUsed=" + r.iterationsUsed(),
                    passed);
        } catch (Exception e) {
            return new EvalResult("iteration_cap_holds", task, "no exception", e.getMessage(), false);
        }
    }

    // ── Case 2: Correct tool selected for weather query ───────────────────────
    private EvalResult evalCorrectToolSelection() {
        String task = "What is the weather in Mumbai?";
        log.info("Eval 2: correct tool selection");
        try {
            AgentResponse r = orchestrator.run(task);
            boolean passed = r.toolsInvoked().contains("get_weather");
            return new EvalResult("correct_tool_selection", task,
                    "toolsInvoked contains 'get_weather'",
                    "toolsInvoked=" + r.toolsInvoked(),
                    passed);
        } catch (Exception e) {
            return new EvalResult("correct_tool_selection", task, "no exception", e.getMessage(), false);
        }
    }

    // ── Case 3: Invalid/unknown tool handled gracefully ───────────────────────
    // The guardrail in AgentOrchestrator returns an error observation
    // instead of crashing. Agent should recover or give a graceful answer.
    private EvalResult evalInvalidToolHandled() {
        String task = "What is the current stock price of TCS?";
        log.info("Eval 3: invalid tool handled");
        try {
            AgentResponse r = orchestrator.run(task);
            // Pass: agent completed without throwing an exception
            // (it may say it can't find the data — that's acceptable)
            boolean passed = r.answer() != null && !r.answer().isBlank();
            return new EvalResult("invalid_tool_handled", task,
                    "agent returns non-empty answer without crashing",
                    "answer length=" + (r.answer() != null ? r.answer().length() : 0),
                    passed);
        } catch (Exception e) {
            return new EvalResult("invalid_tool_handled", task, "no exception", e.getMessage(), false);
        }
    }

    // ── Case 4: Weather tool returns real data ────────────────────────────────
    private EvalResult evalWeatherToolWorks() {
        String task = "What is the weather in Bengaluru right now?";
        log.info("Eval 4: weather tool");
        try {
            AgentResponse r = orchestrator.run(task);
            boolean passed = r.toolsInvoked().contains("get_weather")
                    && !r.hitIterationLimit()
                    && r.answer() != null && r.answer().length() > 10;
            return new EvalResult("weather_tool_works", task,
                    "get_weather called, answer non-empty, no limit hit",
                    "tools=" + r.toolsInvoked() + " answerLen=" + r.answer().length(),
                    passed);
        } catch (Exception e) {
            return new EvalResult("weather_tool_works", task, "no exception", e.getMessage(), false);
        }
    }

    // ── Case 5: DB tool returns product data ──────────────────────────────────
    private EvalResult evalDBToolWorks() {
        String task = "List all Electronics products from the database.";
        log.info("Eval 5: DB tool");
        try {
            AgentResponse r = orchestrator.run(task);
            boolean passed = r.toolsInvoked().contains("lookup_products")
                    && !r.hitIterationLimit()
                    && r.answer() != null && r.answer().length() > 10;
            return new EvalResult("db_tool_works", task,
                    "lookup_products called, answer non-empty, no limit hit",
                    "tools=" + r.toolsInvoked() + " answerLen=" + r.answer().length(),
                    passed);
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
}
