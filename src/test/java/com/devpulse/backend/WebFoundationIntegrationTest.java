package com.devpulse.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 1: the pieces every screen relies on — CORS for the React dev
 * server, one JSON error shape, and the OpenAPI docs.
 */
class WebFoundationIntegrationTest extends AbstractIntegrationTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:5173";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void corsPreflightFromFrontendIsAllowed() {

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/login", HttpMethod.OPTIONS, preflight(FRONTEND_ORIGIN), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(FRONTEND_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
    }

    @Test
    void corsPreflightFromUnknownOriginIsRejected() {

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/login", HttpMethod.OPTIONS, preflight("https://evil.example"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void protectedEndpointWithoutTokenReturnsJson401() {

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/profile", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody())
                .containsEntry("status", 401)
                .containsEntry("error", "Unauthorized")
                .containsEntry("path", "/api/profile")
                .containsKeys("timestamp", "message");
    }

    @Test
    void validationErrorListsEachInvalidField() {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/register",
                new HttpEntity<>("{\"name\":\"\",\"email\":\"not-an-email\",\"password\":\"\"}", headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("message", "Validation failed");

        List<Map<String, String>> errors = (List<Map<String, String>>) response.getBody().get("errors");
        assertThat(errors).extracting(e -> e.get("field")).contains("email");
    }

    @Test
    void malformedJsonBodyReturns400NotA500() {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/auth/login", new HttpEntity<>("{not json", headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", 400);
    }

    @Test
    void openApiDocsArePublicAndDescribeTheApi() {

        ResponseEntity<Map> response = restTemplate.getForEntity("/v3/api-docs", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) response.getBody().get("paths"))
                .containsKeys("/api/auth/login", "/api/auth/register");

        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        assertThat((Map<String, Object>) components.get("schemas")).containsKey("ErrorResponse");
    }

    private static HttpEntity<Void> preflight(String origin) {

        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(HttpMethod.POST);
        headers.setAccessControlRequestHeaders(List.of(HttpHeaders.CONTENT_TYPE));
        return new HttpEntity<>(headers);
    }
}
