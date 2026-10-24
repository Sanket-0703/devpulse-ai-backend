package com.devpulse.backend;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.LoginRequest;
import com.devpulse.backend.dto.RegisterRequest;
import com.devpulse.backend.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the whole stack: Flyway migrations, Hibernate schema
 * validation, PostgreSQL, Redis, security and the auth flow.
 */
class BackendApplicationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void healthIsUp() {

        ResponseEntity<Map> response =
                restTemplate.getForEntity("/actuator/health", Map.class);

        // The aggregate status includes the db and redis health indicators.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    void flywayAppliesAllMigrationsAndEnablesPgvector() {

        List<String> versions = jdbcTemplate.queryForList(
                "select version from flyway_schema_history "
                        + "where success order by installed_rank",
                String.class
        );

        Integer vectorExtensions = jdbcTemplate.queryForObject(
                "select count(*) from pg_extension where extname = 'vector'",
                Integer.class
        );

        assertThat(versions).containsExactly("1", "2", "3", "4", "5");
        assertThat(vectorExtensions).isEqualTo(1);
    }

    @Test
    void registerLoginProfileLogoutRoundTrip() {

        String email = "it-" + UUID.randomUUID() + "@example.com";
        String password = "Passw0rd!23";

        RegisterRequest register = new RegisterRequest();
        register.setName("Integration Test");
        register.setEmail(email);
        register.setPassword(password);

        ResponseEntity<String> registered =
                restTemplate.postForEntity("/api/auth/register", register, String.class);

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        LoginRequest login = new LoginRequest();
        login.setEmail(email);
        login.setPassword(password);

        ResponseEntity<AuthResponse> loggedIn =
                restTemplate.postForEntity("/api/auth/login", login, AuthResponse.class);

        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loggedIn.getBody()).isNotNull();
        assertThat(loggedIn.getBody().accessToken()).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(loggedIn.getBody().accessToken());
        HttpEntity<Void> authenticated = new HttpEntity<>(headers);

        ResponseEntity<UserResponse> profile = restTemplate.exchange(
                "/api/profile", HttpMethod.GET, authenticated, UserResponse.class);

        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody()).isNotNull();
        assertThat(profile.getBody().email()).isEqualTo(email);

        ResponseEntity<Void> loggedOut = restTemplate.exchange(
                "/api/auth/logout", HttpMethod.POST, authenticated, Void.class);

        assertThat(loggedOut.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The token is blacklisted in Redis after logout.
        ResponseEntity<String> afterLogout = restTemplate.exchange(
                "/api/profile", HttpMethod.GET, authenticated, String.class);

        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
