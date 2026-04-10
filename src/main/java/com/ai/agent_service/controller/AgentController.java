package com.ai.agent_service.controller;

import com.ai.agent_service.evaluator.AgentEvaluator;
import com.ai.agent_service.kafka.AIEventProducer;
import com.ai.agent_service.model.AgentRequest;
import com.ai.agent_service.model.AgentResponse;
import com.ai.agent_service.model.*;
import com.ai.agent_service.orchestrator.AgentOrchestrator;
import com.ai.agent_service.tool.ToolRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * Day 39: Production Spring endpoint.
 *
 * POST /agent        — run agent on a task
 * GET  /agent/tools  — list registered tools (useful for debugging + clients)
 * GET  /agent/health — liveness check
 */
@RestController
@RequestMapping("/agent")
@CrossOrigin(origins = "http://localhost:4200")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final int MAX_TASK_LENGTH = 1000;

    private final AgentOrchestrator orchestrator;
    private final ToolRegistry toolRegistry;
    private final AgentEvaluator evaluator;
    private final AIEventProducer producer;
    private final JdbcTemplate jdbc;

    public AgentController(AgentOrchestrator orchestrator, ToolRegistry toolRegistry, AgentEvaluator evaluator, AIEventProducer producer, JdbcTemplate jdbc) {
        this.orchestrator = orchestrator;
        this.toolRegistry = toolRegistry;
        this.evaluator = evaluator;
        this.producer = producer;
        this.jdbc = jdbc;
    }

    // ── POST /agent ───────────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<?> runAgent(@RequestBody AgentRequest request) {

        // Request validation
        if (request == null || request.task() == null || request.task().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Field 'task' is required and cannot be blank."));
        }
        if (request.task().length() > MAX_TASK_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Field 'task' exceeds max length of " + MAX_TASK_LENGTH + " characters."));
        }

        log.info("POST /agent — task: '{}'", request.task());
        long start = System.currentTimeMillis();

        AgentResponse response = orchestrator.run(request.task());

        switch (response) {
            case SuccessResponse(var answer, var iterationsUsed, var maxIterations,
                                 var toolsInvoked, var durationMs, var totalTokensUsed) ->
                    log.info("POST /agent completed — {}ms, iterations: {}, tools: {}",
                            System.currentTimeMillis() - start, iterationsUsed, toolsInvoked);
            case BudgetExceededResponse(var totalTokensUsed, var maxCostTokens,
                                        var toolsInvoked, var durationMs) ->
                    log.warn("POST /agent budget exceeded — tokens: {}/{}, tools: {}",
                            totalTokensUsed, maxCostTokens, toolsInvoked);
            case ErrorResponse(var message, var toolsInvoked, var durationMs, var totalTokensUsed) ->
                    log.error("POST /agent failed — {}, tools: {}", message, toolsInvoked);
            case IterationLimitResponse(var maxIterations, var toolsInvoked,
                                        var durationMs, var totalTokensUsed) ->
                    log.warn("POST /agent hit iteration limit — max: {}, tools: {}",
                            maxIterations, toolsInvoked);
        }

        // If agent hit a limit, return 200 with the partial answer
        // (it's not a server error — the agent ran, just didn't fully complete)
        return ResponseEntity.ok(response);
    }

    // ── GET /agent/tools ──────────────────────────────────────────────────────
    @GetMapping("/tools")
    public ResponseEntity<?> listTools() {
        var tools = toolRegistry.getAllTools().entrySet().stream()
                .map(e -> Map.of(
                        "name", e.getKey(),
                        "description", e.getValue().description()
                ))
                .toList();
        return ResponseEntity.ok(Map.of("count", tools.size(), "tools", tools));
    }

    // ── GET /agent/health ─────────────────────────────────────────────────────
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "toolsLoaded", toolRegistry.getToolCount()
        ));
    }

    @GetMapping("/eval")
    public ResponseEntity<?> runEval() {
        log.info("GET /agent/eval — running evaluation suite");
        AgentEvaluator.EvalReport report = evaluator.runAll();
        return ResponseEntity.ok(report);
    }

    @PostMapping("/async")
    public ResponseEntity<Map<String, String>> async(@RequestBody AgentRequest req) {
        String requestId = producer.publish("default-user", req.task(), "REACT", 5000);
        return ResponseEntity.accepted().body(Map.of("requestId", requestId));
    }

    @GetMapping("/result/{requestId}")
    public ResponseEntity<?> result(@PathVariable String requestId) {
        return jdbc.query(
                "SELECT * FROM agent_results WHERE request_id = ?",
                rs -> rs.next()
                        ? ResponseEntity.ok(mapRow(rs))
                        : ResponseEntity.accepted().body(Map.of("status", "PROCESSING")),
                requestId);
    }

    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        return Map.of(
                "requestId",    rs.getString("request_id"),
                "userId",       rs.getString("user_id"),
                "result",       rs.getString("result"),
                "status",       rs.getString("status"),
                "processingMs", rs.getLong("processing_ms")
        );
    }
}
