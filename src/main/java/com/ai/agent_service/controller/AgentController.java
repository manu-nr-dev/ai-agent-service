package com.ai.agent_service.controller;

import com.ai.agent_service.model.AgentResponse;
import com.ai.agent_service.orchestrator.AgentOrchestrator;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent")
public class AgentController {
    private final AgentOrchestrator agentOrchestrator;

    public AgentController(AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    @PostMapping("/call")
    public AgentResponse call(@RequestBody String message){
        return agentOrchestrator.run(message);
    }
}
