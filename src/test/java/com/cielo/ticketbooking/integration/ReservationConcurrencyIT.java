package com.cielo.ticketbooking.integration;

import com.cielo.ticketbooking.application.dto.CreateEventRequest;
import com.cielo.ticketbooking.application.dto.EventAvailabilityResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationConcurrencyIT {

    private static final Logger log = LoggerFactory.getLogger(ReservationConcurrencyIT.class);
    private static final int CLIENT_COUNT = 50;
    private static final int INITIAL_STOCK = 5;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("50 reservas simultâneas para 5 ingressos devem resultar em 5 sucessos e 45 conflitos")
    void shouldPreventOversellingUnderHighConcurrency() throws Exception {
        var creation = restTemplate.postForEntity(
                "/events",
                new CreateEventRequest("Concurrent Flash Sale", INITIAL_STOCK),
                EventAvailabilityResponse.class
        );
        assertThat(creation.getStatusCode().value()).isEqualTo(201);
        EventAvailabilityResponse event = creation.getBody();
        assertThat(event).isNotNull();

        ExecutorService clients = Executors.newFixedThreadPool(CLIENT_COUNT);
        CountDownLatch ready = new CountDownLatch(CLIENT_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResult>> requests = new ArrayList<>(CLIENT_COUNT);
        String runId = UUID.randomUUID().toString();

        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            URI reservationUri = URI.create(
                    "http://localhost:" + port + "/events/" + event.id() + "/reservations"
            );

            for (int client = 0; client < CLIENT_COUNT; client++) {
                int clientId = client;
                requests.add(clients.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent request start");
                    }

                    String payload = """
                            {"customerId":"customer-%d","quantity":1}
                            """.formatted(clientId);
                    HttpRequest request = HttpRequest.newBuilder(reservationUri)
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "concurrent-" + runId + "-" + clientId)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build();
                    HttpResponse<String> response = httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );
                    return new HttpResult(clientId, response.statusCode(), response.body());
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("all %s clients should be ready before starting the requests", CLIENT_COUNT)
                    .isTrue();
            start.countDown();

            List<HttpResult> results = requests.stream()
                    .map(this::getResult)
                    .toList();

            results.forEach(result -> log.info(
                    "Concurrency run={} client={} POST /events/{}/reservations -> HTTP {}",
                    runId,
                    result.clientId(),
                    event.id(),
                    result.statusCode()
            ));

            assertThat(results).hasSize(CLIENT_COUNT);
            assertThat(results).as("HTTP responses: %s", results)
                    .filteredOn(result -> result.statusCode() == 201)
                    .hasSize(INITIAL_STOCK);
            assertThat(results).filteredOn(result -> result.statusCode() == 409)
                    .hasSize(CLIENT_COUNT - INITIAL_STOCK)
                    .allSatisfy(result -> assertThat(result.body())
                            .contains("\"status\":409")
                            .contains("Tickets sold out"));
            assertThat(results).allSatisfy(result ->
                    assertThat(result.statusCode()).isIn(201, 409));

            Integer databaseStock = jdbcTemplate.queryForObject(
                    "SELECT available_capacity FROM events WHERE id = ?",
                    Integer.class,
                    event.id()
            );
            Integer reservationCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM reservations WHERE event_id = ?",
                    Integer.class,
                    event.id()
            );
            String redisStock = redisTemplate.opsForValue()
                    .get("event:" + event.id() + ":available");

            long successfulRequests = results.stream()
                    .filter(result -> result.statusCode() == 201)
                    .count();
            long rejectedRequests = results.stream()
                    .filter(result -> result.statusCode() == 409)
                    .count();
            log.info(
                    "Concurrency run={} complete: requests={}, created={}, conflicts={}, " +
                            "reservations={}, PostgreSQL stock={}, Redis stock={}",
                    runId,
                    results.size(),
                    successfulRequests,
                    rejectedRequests,
                    reservationCount,
                    databaseStock,
                    redisStock
            );

            assertThat(databaseStock).isZero();
            assertThat(reservationCount).isEqualTo(INITIAL_STOCK);
            assertThat(redisStock).isEqualTo("0");
        } finally {
            start.countDown();
            clients.shutdownNow();
        }
    }

    private HttpResult getResult(Future<HttpResult> request) {
        try {
            return request.get(40, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new AssertionError("A concurrent reservation request did not complete successfully", ex);
        }
    }

    private record HttpResult(int clientId, int statusCode, String body) {
    }
}
