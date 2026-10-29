package com.cielo.ticketbooking.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MultiInstanceReservationIT {

    private static final Logger log = LoggerFactory.getLogger(MultiInstanceReservationIT.class);
    private static final int CLIENT_COUNT = 50;
    private static final int INITIAL_STOCK = 5;
    private static final String UPSTREAM_HEADER = "X-Upstream-Addr";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Deve balancear reservas entre duas instâncias sem vender acima do estoque")
    void shouldBalanceConcurrentReservationsAcrossApplicationInstances() throws Exception {
        String baseUrl = System.getProperty(
                "ticket-booking.base-url",
                System.getenv().getOrDefault("TICKET_BOOKING_BASE_URL", "http://localhost:8080")
        );
        URI baseUri = URI.create(baseUrl);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Set<String> readyInstances = awaitBothInstancesReady(httpClient, baseUri);
        log.info("Nginx health checks reached application instances: {}", readyInstances);

        HttpResponse<String> eventResponse = httpClient.send(
                HttpRequest.newBuilder(baseUri.resolve("/events"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"title\":\"Multi-instance flash sale\",\"totalCapacity\":5}"
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(eventResponse.statusCode())
                .as("event creation response: %s", eventResponse.body())
                .isEqualTo(201);
        UUID eventId = UUID.fromString(objectMapper.readTree(eventResponse.body()).get("id").asText());

        ExecutorService clients = Executors.newFixedThreadPool(CLIENT_COUNT);
        CountDownLatch ready = new CountDownLatch(CLIENT_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReservationResult>> requests = new ArrayList<>(CLIENT_COUNT);
        String runId = UUID.randomUUID().toString();

        try {
            URI reservationUri = baseUri.resolve("/events/" + eventId + "/reservations");
            for (int clientId = 0; clientId < CLIENT_COUNT; clientId++) {
                int requestId = clientId;
                requests.add(clients.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for the concurrent test start");
                    }

                    String payload = "{\"customerId\":\"multi-instance-" + requestId + "\",\"quantity\":1}";
                    HttpRequest request = HttpRequest.newBuilder(reservationUri)
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "multi-instance-" + runId + "-" + requestId)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build();
                    HttpResponse<String> response = httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );
                    String upstream = response.headers()
                            .firstValue(UPSTREAM_HEADER)
                            .orElseThrow(() -> new AssertionError(
                                    "Missing " + UPSTREAM_HEADER + " header from Nginx"
                            ));
                    return new ReservationResult(requestId, response.statusCode(), response.body(), upstream);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("all %s client requests should be ready", CLIENT_COUNT)
                    .isTrue();
            start.countDown();

            List<ReservationResult> results = requests.stream()
                    .map(this::getResult)
                    .toList();

            results.forEach(result -> log.info(
                    "Multi-instance run={} client={} status={} upstream={}",
                    runId,
                    result.clientId(),
                    result.statusCode(),
                    result.upstream()
            ));

            assertThat(results).hasSize(CLIENT_COUNT);
            assertThat(results).as("responses: %s", results)
                    .filteredOn(result -> result.statusCode() == 201)
                    .hasSize(INITIAL_STOCK);
            assertThat(results).filteredOn(result -> result.statusCode() == 409)
                    .hasSize(CLIENT_COUNT - INITIAL_STOCK)
                    .allSatisfy(result -> assertThat(result.body())
                            .contains("\"status\":409")
                            .contains("Tickets sold out"));
            assertThat(results).allSatisfy(result ->
                    assertThat(result.statusCode()).isIn(201, 409));

            Set<String> upstreamInstances = new HashSet<>();
            results.forEach(result -> {
                for (String upstream : result.upstream().split(",")) {
                    upstreamInstances.add(upstream.trim());
                }
            });
            assertThat(upstreamInstances)
                    .as("Nginx upstreams observed across the reservation requests")
                    .hasSizeGreaterThanOrEqualTo(2);

            HttpResponse<String> availabilityResponse = httpClient.send(
                    HttpRequest.newBuilder(baseUri.resolve("/events/" + eventId))
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(availabilityResponse.statusCode())
                    .as("availability response: %s", availabilityResponse.body())
                    .isEqualTo(200);
            assertThat(objectMapper.readTree(availabilityResponse.body()).get("availableCapacity").asInt())
                    .isZero();
            log.info(
                    "Multi-instance run={} complete: requests={}, created={}, conflicts={}, " +
                            "upstreams={}, availableStock={}",
                    runId,
                    results.size(),
                    results.stream().filter(result -> result.statusCode() == 201).count(),
                    results.stream().filter(result -> result.statusCode() == 409).count(),
                    upstreamInstances,
                    objectMapper.readTree(availabilityResponse.body()).get("availableCapacity").asInt()
            );
        } finally {
            start.countDown();
            clients.shutdownNow();
        }
    }

    private Set<String> awaitBothInstancesReady(HttpClient httpClient, URI baseUri) throws Exception {
        URI healthUri = baseUri.resolve("/actuator/health");
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        Set<String> instances = new HashSet<>();

        while (System.nanoTime() < deadline && instances.size() < 2) {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(healthUri)
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(response.statusCode())
                    .as("health check response: %s", response.body())
                    .isEqualTo(200);
            response.headers().firstValue(UPSTREAM_HEADER).ifPresent(instances::add);
            if (instances.size() < 2) {
                TimeUnit.MILLISECONDS.sleep(200);
            }
        }

        assertThat(instances)
                .as("both application instances should be reachable through the load balancer")
                .hasSizeGreaterThanOrEqualTo(2);
        return instances;
    }

    private ReservationResult getResult(Future<ReservationResult> request) {
        try {
            return request.get(40, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("A reservation request did not complete successfully", exception);
        }
    }

    private record ReservationResult(int clientId, int statusCode, String body, String upstream) {
    }
}
