# ai-agent-service

**Day 36–40 of the 65-Day AI Engineer Roadmap**

A Spring Boot service that implements an AI agent using the **ReAct pattern** (Reason + Act).

## What This Is

An agent is not a chatbot. A chatbot answers questions. An agent **completes tasks** — it reasons, calls tools, observes results, and loops until it has an answer.

This project builds that loop in plain Java + Spring, from scratch.

## Architecture

```
POST /agent  →  AgentController
                    ↓
             AgentOrchestrator  ←─── the ReAct loop
             ┌──────────────────────────────────────┐
             │  while (iterations < MAX):           │
             │    1. Build prompt (task + history)  │
             │    2. Call LLM → get decision        │
             │    3. Final answer? → return         │
             │    4. Tool call? → execute → observe │
             └──────────────────────────────────────┘
                    ↓
             ToolRegistry  →  ToolFunction implementations
```

## Project Structure

```
src/main/java/com/manu/agent/
├── AgentServiceApplication.java        # Spring Boot entry point
├── orchestrator/
│   └── AgentOrchestrator.java          # The ReAct loop
├── tool/
│   ├── ToolFunction.java               # Interface every tool implements
│   ├── ToolRegistry.java               # Auto-discovers all ToolFunction beans
│   └── GetCurrentTimeTool.java         # Day 36: first tool
├── controller/
│   └── AgentController.java            # POST /agent
├── model/
│   ├── AgentRequest.java               # {task: string}
│   ├── AgentResponse.java              # {answer, iterationsUsed, toolsInvoked, ...}
│   ├── LlmDecision.java                # Parsed LLM output: tool call OR final answer
│   └── ToolCall.java                   # {toolName, args}
└── config/
    └── AgentConfig.java                # ChatClient + ObjectMapper beans
```

## Running It

### Prerequisites
- Java 17
- Maven
- Gemini API key (free tier, [Google AI Studio](https://aistudio.google.com/))

### Start
```bash
export GEMINI_API_KEY=your_key_here
mvn spring-boot:run
```
Service starts on **port 8081**.

### Test the ReAct loop
```bash
curl -X POST http://localhost:8081/agent \
  -H "Content-Type: application/json" \
  -d '{"task": "What day of the week is it today? Tell me in a friendly way."}'
```

Expected response:
```json
{
  "answer": "Today is Saturday, March 21, 2026! Hope you're having a great weekend!",
  "iterationsUsed": 2,
  "maxIterations": 10,
  "toolsInvoked": ["get_current_time"],
  "hitIterationLimit": false,
  "durationMs": 1423
}
```

### Health check
```bash
curl http://localhost:8081/agent/health
```

## How the ReAct Loop Works

Iteration 1:
- LLM receives task: "What day of the week is it today?"
- LLM responds: `{"thought": "I need to know the current time", "action": "get_current_time", "action_input": {}}`
- Tool executes → returns "Current date and time: Saturday, March 21 2026, 14:30:00 IST"
- Observation injected into history

Iteration 2:
- LLM receives task + previous thought + observation
- LLM responds: `{"thought": "I now know the date", "final_answer": "Today is Saturday..."}`
- Loop exits, answer returned

## Adding a New Tool (Day 37+)

1. Create a class implementing `ToolFunction`
2. Annotate with `@Component`
3. Spring auto-registers it in `ToolRegistry`
4. No other changes needed

```java
@Component
public class SearchDatabaseTool implements ToolFunction {
    @Override public String name() { return "search_database"; }
    @Override public String description() { return "Search the knowledge base for information..."; }
    @Override public String execute(Map<String, String> args) { ... }
}
```

## Failure Modes (Day 40)

| Mode | Cause | Guardrail |
|------|-------|-----------|
| Infinite loop | Agent never converges | Hard cap: `agent.max-iterations=10` |
| Wrong tool | Bad tool description | Rewrite description; test with adversarial queries |
| Hallucinated args | LLM invents params | Schema validation in ToolRegistry |
| Context overflow | Long loops fill context | Summarize history after N iterations |
| Cost explosion | 15 iterations × LLM cost | Cost budget check (add Day 40) |

## Roadmap

- **Day 36** ✅ Agent skeleton + ReAct loop + GetCurrentTimeTool
- **Day 37** — DBLookupTool + HttpCallTool
- **Day 38** — Agent loop hardening (context summarisation, cost budget)
- **Day 39** — Production Spring endpoint (streaming, auth)
- **Day 40** — Evaluation: test all 5 failure modes, add guardrails
