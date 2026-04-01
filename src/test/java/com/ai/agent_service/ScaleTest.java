package com.ai.agent_service;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ScaleTest {

    private static final Logger log = LoggerFactory.getLogger(ScaleTest.class);

    @LocalServerPort
    private int port;

    private TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    public void send1000Events() throws InterruptedException {
        int total = 100;
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(total);

        long testStart = System.currentTimeMillis();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    long start = System.currentTimeMillis();
                    Map<String, String> body = Map.of("task", "What is 2 + " + idx + "?");
                    ResponseEntity<Map> response = restTemplate.postForEntity(
                            "http://localhost:" + port + "/agent/async",
                            body,
                            Map.class
                    );
                    latencies.add(System.currentTimeMillis() - start);
                    if (response.getStatusCode().is2xxSuccessful()) success.incrementAndGet();
                    else failed.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.MINUTES);
        executor.shutdown();

        long totalMs = System.currentTimeMillis() - testStart;
        latencies.sort(Long::compareTo);

        log.info("=== SCALE TEST RESULTS ===");
        log.info("Total:       {}", total);
        log.info("Success:     {}", success.get());
        log.info("Failed:      {}", failed.get());
        log.info("Duration:    {}ms", totalMs);
        log.info("Throughput:  {} req/s", (total * 1000L) / totalMs);
        log.info("Latency p50: {}ms", latencies.get((int)(total * 0.50)));
        log.info("Latency p95: {}ms", latencies.get((int)(total * 0.95)));
        log.info("Latency p99: {}ms", latencies.get((int)(total * 0.99)));
        log.info("Latency max: {}ms", latencies.get(total - 1));
    }
}
