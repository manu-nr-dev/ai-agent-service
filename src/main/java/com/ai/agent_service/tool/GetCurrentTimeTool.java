package com.ai.agent_service.tool;

import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Day 36 tool: returns the current date and time.
 *
 * Simple but purposeful — it makes the full ReAct loop executable today.
 * Thought → Action(get_current_time) → Observation(2026-03-21...) → Final Answer.
 *
 * On Day 37 this is joined by DBLookupTool and HttpCallTool.
 *
 * Notice how the description tells the LLM WHEN to use this tool.
 * That phrasing — "Use this when the task requires knowing the current time"
 * — is what drives correct tool selection. Vague descriptions cause wrong picks.
 */
@Component
public class GetCurrentTimeTool implements ToolFunction {

    @Override
    public String name() {
        return "get_current_time";
    }

    @Override
    public String description() {
        return "Returns the current date and time in a human-readable format. "
                + "Use this when the task requires knowing the current time, date, "
                + "day of the week, or any time-related information.";
    }

    @Override
    public String execute(Map<String, String> args) {
        // args are ignored for this tool — no input needed
        ZonedDateTime now = ZonedDateTime.now();
        return "Current date and time: "
                + now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy, HH:mm:ss z"));
    }
}
