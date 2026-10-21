package com.devpulse.backend;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.UserResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 2: the full auth flow over HTTP against real PostgreSQL and Redis —
 * register, login, /me, refresh-token rotation, logout, and every way a
 * token can be bad (always a JSON 401, never a 500).
 */
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String COOKIE = "devpulse_refresh";
    private static final String PASSWORD = "Passw0rd!23";

    @Autowired
    private TestRestTemplate rest;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String email;

    @BeforeEach
    void uniqueEmail() {
        email = "auth-" + UUID.randomUUID() + "@example.com";
    }

    // ---------- register ----------

    @Test
    void registerReturns201WithTheUserAndNoPassword() {

        ResponseEntity<Map> response = register("  Ada Lovelace ", email.toUpperCase());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .containsEntry("name", "Ada Lovelace")
                .containsEntry("email", email)
                .containsEntry("role", "ROLE_USER")
                .containsKey("id")
                .doesNotContainKey("password");
    }

    @Test
    void registerRejectsADuplicateEmailInAnyCase() {

        register("Ada", email);

        ResponseEntity<Map> duplicate = register("Ada again", email.toUpperCase());

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("message", "An account with this email already exists");
    }

    @Test
    void registerRejectsAPasswordLongerThanBcryptCanHash() {

        ResponseEntity<Map> response = rest.postForEntity("/api/auth/register",
                Map.of("name", "Ada", "email", email, "password", "a".repeat(73)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- login ----------

    @Test
    void loginReturnsAnAccessTokenAndSetsASecureRefreshCookie() {

        register("Ada", email);

        ResponseEntity<AuthResponse> response = login(email.toUpperCase(), PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isNotBlank();
        assertThat(response.getBody().tokenType()).isEqualTo("Bearer");
        assertThat(response.getBody().expiresIn()).isEqualTo(900);
        assertThat(response.getBody().user().email()).isEqualTo(email);

        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith(COOKIE + "=")
                .contains("HttpOnly", "SameSite=Strict", "Path=/api/auth");
        // 7 days, give or take the milliseconds between issuing the token and writing the cookie.
        assertThat(HttpCookie.parse(setCookie).getFirst().getMaxAge()).isBetween(604_795L, 604_800L);
    }

    @Test
    void loginWithAWrongPasswordOrUnknownEmailGivesTheSameError() {

        register("Ada", email);

        ResponseEntity<Map> wrongPassword = rest.postForEntity("/api/auth/login",
                Map.of("email", email, "password", "wrong-password"), Map.class);
        ResponseEntity<Map> unknownEmail = rest.postForEntity("/api/auth/login",
                Map.of("email", "nobody-" + email, "password", PASSWORD), Map.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().get("message"))
                .isEqualTo(unknownEmail.getBody().get("message"))
                .isEqualTo("Invalid email or password");
    }

    // ---------- /me ----------

    @Test
    void meReturnsTheSignedInUser() {

        register("Ada", email);
        String accessToken = login(email, PASSWORD).getBody().accessToken();

        ResponseEntity<UserResponse> me = rest.exchange(
                "/api/auth/me", HttpMethod.GET, withBearer(accessToken), UserResponse.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().email()).isEqualTo(email);
        assertThat(me.getBody().name()).isEqualTo("Ada");
    }

    @Test
    void meWithoutATokenIs401() {

        ResponseEntity<Map> me = rest.getForEntity("/api/auth/me", Map.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(me.getBody()).containsEntry("message", "Authentication required");
    }

    // ---------- refresh ----------

    @Test
    void refreshRotatesTheCookieAndReturnsANewAccessToken() {

        register("Ada", email);
        String firstCookie = refreshCookie(login(email, PASSWORD));

        ResponseEntity<AuthResponse> refreshed = refresh(firstCookie);

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody().accessToken()).isNotBlank();
        String secondCookie = refreshCookie(refreshed);
        assertThat(secondCookie).isNotEqualTo(firstCookie);

        // The new access token works.
        ResponseEntity<UserResponse> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                withBearer(refreshed.getBody().accessToken()), UserResponse.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The old refresh token can't be used again. Reuse within seconds looks
        // like two tabs refreshing at once, so the cookie is left alone (the
        // browser already holds the newer one) and the newest token keeps
        // working (see RefreshTokenServiceImpl).
        ResponseEntity<Map> reused = rest.exchange("/api/auth/refresh", HttpMethod.POST, withCookie(firstCookie), Map.class);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reused.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();

        assertThat(refresh(secondCookie).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void refreshWithoutACookieIs401() {

        ResponseEntity<Map> response = rest.exchange("/api/auth/refresh", HttpMethod.POST, HttpEntity.EMPTY, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("message", "Not signed in");
    }

    @Test
    void refreshWithAMadeUpCookieIs401AndClearsTheCookie() {

        ResponseEntity<Map> response = rest.exchange(
                "/api/auth/refresh", HttpMethod.POST, withCookie("made-up-token"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains(COOKIE + "=;", "Max-Age=0");
    }

    // ---------- logout ----------

    @Test
    void logoutEndsTheSessionEverywhere() {

        register("Ada", email);
        ResponseEntity<AuthResponse> loggedIn = login(email, PASSWORD);
        String accessToken = loggedIn.getBody().accessToken();
        String cookie = refreshCookie(loggedIn);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.add(HttpHeaders.COOKIE, COOKIE + "=" + cookie);
        ResponseEntity<Void> logout = rest.exchange("/api/auth/logout", HttpMethod.POST, new HttpEntity<>(headers), Void.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(logout.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        // The access token is blocked (Redis blacklist)…
        ResponseEntity<Map> me = rest.exchange("/api/auth/me", HttpMethod.GET, withBearer(accessToken), Map.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(me.getBody()).containsEntry("message", "Session has ended, please sign in again");

        // …and the refresh token is revoked.
        assertThat(rest.exchange("/api/auth/refresh", HttpMethod.POST, withCookie(cookie), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutWithNothingStillSucceeds() {

        ResponseEntity<Void> logout = rest.exchange("/api/auth/logout", HttpMethod.POST, HttpEntity.EMPTY, Void.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ---------- bad access tokens: always JSON 401 ----------

    @Test
    void malformedTokenIs401() {
        assertBadToken("not-a-jwt", "Access token is invalid");
    }

    @Test
    void expiredTokenIs401() {

        String expired = Jwts.builder()
                .issuer("devpulse-ai")
                .subject("1")
                .id(UUID.randomUUID().toString())
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertBadToken(expired, "Access token has expired");
    }

    @Test
    void tokenWithForgedSignatureIs401() {

        String forged = Jwts.builder()
                .issuer("devpulse-ai")
                .subject("1")
                .id(UUID.randomUUID().toString())
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(
                        "attacker-secret-0123456789-abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertBadToken(forged, "Access token is invalid");
    }

    @Test
    void tokenForADeletedUserIs401() {

        String orphan = Jwts.builder()
                .issuer("devpulse-ai")
                .subject("999999999")
                .id(UUID.randomUUID().toString())
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertBadToken(orphan, "Account no longer exists");
    }

    @Test
    void badTokenDoesNotBlockPublicEndpoints() {

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-jwt");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = rest.exchange("/api/auth/register", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Ada", "email", email, "password", PASSWORD), headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---------- helpers ----------

    private void assertBadToken(String token, String expectedMessage) {

        ResponseEntity<Map> response = rest.exchange("/api/auth/me", HttpMethod.GET, withBearer(token), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody())
                .containsEntry("status", 401)
                .containsEntry("message", expectedMessage);
    }

    private ResponseEntity<Map> register(String name, String email) {
        return rest.postForEntity("/api/auth/register",
                Map.of("name", name, "email", email, "password", PASSWORD), Map.class);
    }

    private ResponseEntity<AuthResponse> login(String email, String password) {
        return rest.postForEntity("/api/auth/login",
                Map.of("email", email, "password", password), AuthResponse.class);
    }

    private ResponseEntity<AuthResponse> refresh(String cookie) {
        return rest.exchange("/api/auth/refresh", HttpMethod.POST, withCookie(cookie), AuthResponse.class);
    }

    private static String refreshCookie(ResponseEntity<?> response) {

        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("Set-Cookie header").isNotNull();

        return HttpCookie.parse(setCookie).getFirst().getValue();
    }

    private static HttpEntity<Void> withBearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private static HttpEntity<Void> withCookie(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, COOKIE + "=" + value);
        return new HttpEntity<>(headers);
    }
}
