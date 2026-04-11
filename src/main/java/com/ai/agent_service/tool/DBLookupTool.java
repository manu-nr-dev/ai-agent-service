package com.ai.agent_service.tool;

import com.ai.agent_service.model.AgentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Day 37 tool: queries the products table.
 *
 * Supports two modes based on args the LLM passes:
 *   - args has "category" → filter by category
 *   - args has "name"     → search by name (partial match)
 *   - no args             → return all products
 *
 * The LLM decides which args to pass based on the task.
 * Your Java code just executes whatever comes in.
 */
@Component
public class DBLookupTool implements ToolFunction {

    private static final Logger log = LoggerFactory.getLogger(DBLookupTool.class);
    private final JdbcTemplate jdbc;

    public DBLookupTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "lookup_products";
    }

    @Override
    public String description() {
        return "Searches the product database. Use this when the task involves finding products, "
                + "checking prices, or listing items by category. "
                + "Args: 'category' (e.g. Electronics, Furniture) OR 'name' (partial product name). "
                + "Both args are optional — omit to get all products.";
    }

    @Override
    public String execute(Map<String, String> args) {
        String requestId = AgentContext.REQUEST_ID.orElse("unknown");
        log.debug("[requestId={}] db_lookup invoked", requestId);

        List<Map<String, Object>> rows;

        if (args.containsKey("category")) {
            String category = args.get("category");
            log.debug("DB query: category = {}", category);
            rows = jdbc.queryForList(
                    "SELECT id, name, price, category FROM products WHERE category ILIKE ?",
                    "%" + category + "%"
            );
        } else if (args.containsKey("name")) {
            String name = args.get("name");
            log.debug("DB query: name = {}", name);
            rows = jdbc.queryForList(
                    "SELECT id, name, price, category FROM products WHERE name ILIKE ?",
                    "%" + name + "%"
            );
        } else {
            log.debug("DB query: all products");
            rows = jdbc.queryForList(
                    "SELECT id, name, price, category FROM products ORDER BY category, name"
            );
        }

        if (rows.isEmpty()) return "No products found.";

        return rows.stream()
                .map(r -> String.format("[%s] %s — ₹%.2f (%s)",
                        r.get("id"), r.get("name"),
                        r.get("price"), r.get("category")))
                .collect(Collectors.joining("\n"));
    }
}
