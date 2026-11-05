package com.devpulse.backend;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.playground.PlaygroundFinding;
import com.devpulse.backend.dto.playground.PlaygroundResponse;
import com.devpulse.backend.dto.review.AIUsageResponse;
import com.devpulse.backend.entity.FindingSource;
import com.devpulse.backend.repository.PlaygroundRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** Level 8: "paste code → review", hardened (validation, rate limits, usage without storing code). */
class PlaygroundIntegrationTest extends AbstractIntegrationTest {

    private static final String CODE = """
            public List<User> find(String name) {
                System.out.println(name);
                return jdbc.query("SELECT * FROM users WHERE name = '" + name + "'", mapper);
            }""";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PlaygroundRunRepository runRepository;

    @LocalServerPort
    private int port;

    @Test
    void aSnippetIsReviewedByTheRulesAndTheModelOnItsOwnLineNumbers() {
        String token = registerAndLogin();

        ResponseEntity<PlaygroundResponse> response = review(token, "JAVA", CODE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PlaygroundResponse body = response.getBody();
        assertThat(body.findings()).extracting(PlaygroundFinding::source, PlaygroundFinding::title,
                        PlaygroundFinding::line)
                .containsExactly(
                        tuple(FindingSource.RULE, "SQL built from strings", 3),
                        tuple(FindingSource.AI, "Debug output left in", 2));
        assertThat(body.provider()).isEqualTo("mock");
        assertThat(body.promptTokens()).isPositive();
        assertThat(body.estimatedCostUsd()).isEqualByComparingTo("0");

        // Usage counts the run; the code itself isn't stored anywhere.
        AIUsageResponse usage = rest.exchange("/api/reviews/usage", HttpMethod.GET, bearer(token),
                AIUsageResponse.class).getBody();
        assertThat(usage.playgroundRunsToday()).isEqualTo(1);
        assertThat(usage.dailyPlaygroundLimit()).isEqualTo(30);
        assertThat(usage.tokensThisMonth()).isEqualTo(body.promptTokens() + body.completionTokens());
        assertThat(runRepository.findAll()).allSatisfy(run -> assertThat(run.getCodeChars()).isPositive());
    }

    @Test
    void invalidInputIsRejected() {
        String token = registerAndLogin();

        assertThat(review(token, "JAVA", "   ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(review(token, "JAVA", "x".repeat(20_001)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(review(token, "COBOL", "x").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange("/api/playground/review", HttpMethod.POST,
                new HttpEntity<>(Map.of("language", "JAVA", "code", "x")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // The old unprotected endpoint is gone.
        assertThat(rest.exchange("/api/ai/review", HttpMethod.POST, new HttpEntity<>("code", headers(token)),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void moreThanFiveRunsAMinuteAreTooMany() throws Exception {
        String token = registerAndLogin();
        for (int i = 0; i < 5; i++) {
            assertThat(review(token, "PYTHON", "print('hi')").getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        // JDK client: it reports the 429 as is (TestRestTemplate's would retry after Retry-After).
        HttpResponse<String> sixth = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/playground/review"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"language\":\"PYTHON\",\"code\":\"print(1)\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(sixth.statusCode()).isEqualTo(429);
        assertThat(sixth.headers().firstValue(HttpHeaders.RETRY_AFTER)).isPresent();
        assertThat(sixth.body()).contains("at most 5 Playground runs a minute");
    }

    private ResponseEntity<PlaygroundResponse> review(String token, String language, String code) {
        return rest.exchange("/api/playground/review", HttpMethod.POST,
                new HttpEntity<>(Map.of("language", language, "code", code), headers(token)),
                PlaygroundResponse.class);
    }

    private String registerAndLogin() {
        String email = "play-" + UUID.randomUUID() + "@example.com";
        rest.postForEntity("/api/auth/register",
                Map.of("name", "Player", "email", email, "password", "Passw0rd!23"), Map.class);
        return rest.postForEntity("/api/auth/login", Map.of("email", email, "password", "Passw0rd!23"),
                AuthResponse.class).getBody().accessToken();
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpEntity<Void> bearer(String token) {
        return new HttpEntity<>(headers(token));
    }
}
