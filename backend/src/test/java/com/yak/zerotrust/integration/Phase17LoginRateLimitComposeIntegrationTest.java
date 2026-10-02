package com.yak.zerotrust.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies login throttling and that spoofed forwarding headers do not change the peer key. */
@EnabledIfEnvironmentVariable(named = "PHASE17_INTEGRATION", matches = "true")
class Phase17LoginRateLimitComposeIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl = environment("PHASE17_BASE_URL", "http://127.0.0.1:8080");
    private final int maxAttempts = Integer.parseInt(environment("PHASE17_IP_MAX_ATTEMPTS", "20"));
    private final int windowSeconds = Integer.parseInt(environment("PHASE17_WINDOW_SECONDS", "60"));

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void returns429AndRetryAfterWhenOnePeerExceedsTheLoginBudget() throws Exception {
        ObjectNode credentials = JSON.createObjectNode()
                .put("username", "phase17-unknown-user")
                .put("password", "Phase17InvalidPassword!");
        HttpResult limitedResponse = null;
        int requestCount = 0;
        int admittedCount = 0;

        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            HttpResult response = request(credentials, attempt);
            requestCount++;
            if (response.status() == 429) {
                limitedResponse = response;
                break;
            }
            assertThat(response.status())
                    .as("requests admitted to authentication should retain the generic invalid-credentials response")
                    .isEqualTo(401);
            admittedCount++;
        }

        assertThat(admittedCount).isPositive();
        assertThat(limitedResponse)
                .as("the same socket peer should be rate-limited even when X-Forwarded-For varies")
                .isNotNull();
        assertThat(limitedResponse.body().path("error").asText()).isEqualTo("AUTH_RATE_LIMITED");
        assertThat(limitedResponse.body().path("message").asText())
                .isEqualTo("Too many authentication attempts.");
        assertThat(limitedResponse.retryAfterSeconds()).isNotNull();
        assertThat(Long.parseLong(limitedResponse.retryAfterSeconds()))
                .isBetween(1L, (long) windowSeconds);
        assertThat(requestCount).isBetween(1, maxAttempts + 1);
    }

    private HttpResult request(ObjectNode credentials, int attempt) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "198.51.100." + (attempt % 250 + 1))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(credentials)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body() == null || response.body().isBlank()
                ? JSON.getNodeFactory().nullNode()
                : JSON.readTree(response.body());
        return new HttpResult(
                response.statusCode(),
                body,
                response.headers().firstValue("Retry-After").orElse(null)
        );
    }

    private static String environment(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record HttpResult(int status, JsonNode body, String retryAfterSeconds) {
    }
}
