package com.ai.agent_service.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Day 37 tool: fetches current weather from wttr.in.
 *
 * wttr.in is free, no API key needed.
 * Returns a one-line weather summary for any city.
 *
 * Example: GET https://wttr.in/Bengaluru?format=3
 * Returns:  "Bengaluru: ⛅️ +28°C"
 */
@Component
public class WeatherTool implements ToolFunction {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);
    private static final String BASE_URL = "https://wttr.in/%s?format=3";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public String name() {
        return "get_weather";
    }

    @Override
    public String description() {
        return "Returns the current weather for a given city. "
                + "Use this when the task asks about weather, temperature, or conditions in a location. "
                + "Args: 'city' (required) — the city name, e.g. 'Bengaluru', 'Mumbai', 'Delhi'.";
    }

    @Override
    public String execute(Map<String, String> args) throws Exception {
        String city = args.get("city");
        if (city == null || city.isBlank()) {
            return "ERROR: 'city' argument is required for get_weather.";
        }

        // Replace spaces with + for URL encoding
        String encodedCity = city.trim().replace(" ", "+");
        String url = String.format(BASE_URL, encodedCity);
        log.debug("Weather request: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            return "ERROR: Weather service returned status " + response.statusCode();
        }

        String result = response.body().trim();
        log.debug("Weather result: {}", result);
        return result;
    }
}