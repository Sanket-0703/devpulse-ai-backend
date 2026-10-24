package com.devpulse.backend;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.HttpCookie;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 3: profile and settings over HTTP against real PostgreSQL and Redis —
 * name/email updates, password change (signs out other devices), and
 * "sign out everywhere".
 */
class ProfileIntegrationTest extends AbstractIntegrationTest {

    private static final String COOKIE = "devpulse_refresh";
    private static final String PASSWORD = "Passw0rd!23";
    private static final String NEW_PASSWORD = "Brand-New-Passw0rd";

    @Autowired
    private TestRestTemplate rest;

    private String email;

    @BeforeEach
    void registerUser() {
        email = "profile-" + UUID.randomUUID() + "@example.com";
        rest.postForEntity("/api/auth/register",
                Map.of("name", "Ada", "email", email, "password", PASSWORD), Map.class);
    }

    // ---------- read ----------

    @Test
    void profileIncludesWhenTheAccountWasCreated() {

        ResponseEntity<UserResponse> profile = rest.exchange(
                "/api/profile", HttpMethod.GET, bearer(login(email, PASSWORD).getBody().accessToken()), UserResponse.class);

        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profile.getBody().createdAt()).isBefore(Instant.now().plusSeconds(1));
    }

    // ---------- name and email ----------

    @Test
    void changingTheNameDoesNotNeedThePassword() {

        String token = login(email, PASSWORD).getBody().accessToken();

        ResponseEntity<UserResponse> updated = updateProfile(token, "Ada Lovelace", email, null, UserResponse.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().name()).isEqualTo("Ada Lovelace");

        // /me sees the change too: it reads the database, not the token.
        ResponseEntity<UserResponse> me = rest.exchange("/api/auth/me", HttpMethod.GET, bearer(token), UserResponse.class);
        assertThat(me.getBody().name()).isEqualTo("Ada Lovelace");
    }

    @Test
    void changingTheEmailNeedsTheCurrentPassword() {

        String token = login(email, PASSWORD).getBody().accessToken();
        String newEmail = "new-" + email;

        ResponseEntity<Map> missing = updateProfile(token, "Ada", newEmail, null, Map.class);
        ResponseEntity<Map> wrong = updateProfile(token, "Ada", newEmail, "wrong-password", Map.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldErrors(missing)).containsEntry("currentPassword", "Enter your current password to change your email");
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldErrors(wrong)).containsEntry("currentPassword", "Current password is incorrect");
    }

    @Test
    void changingTheEmailWithThePasswordMovesTheLogin() {

        String token = login(email, PASSWORD).getBody().accessToken();
        String newEmail = "new-" + email;

        ResponseEntity<UserResponse> updated = updateProfile(token, "Ada", newEmail.toUpperCase(), PASSWORD, UserResponse.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().email()).isEqualTo(newEmail);
        // The access token carries the user id, not the email, so it keeps working.
        assertThat(rest.exchange("/api/auth/me", HttpMethod.GET, bearer(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(login(newEmail, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void cannotTakeAnotherAccountsEmail() {

        String otherEmail = "other-" + email;
        rest.postForEntity("/api/auth/register",
                Map.of("name", "Grace", "email", otherEmail, "password", PASSWORD), Map.class);
        String token = login(email, PASSWORD).getBody().accessToken();

        ResponseEntity<Map> response = updateProfile(token, "Ada", otherEmail.toUpperCase(), PASSWORD, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---------- password ----------

    @Test
    void wrongCurrentPasswordIsAFieldErrorNotA401() {

        String token = login(email, PASSWORD).getBody().accessToken();

        ResponseEntity<Map> response = changePassword(token, "wrong-password", NEW_PASSWORD, Map.class);

        // 400, not 401: the session is fine, only the form is wrong.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldErrors(response)).containsEntry("currentPassword", "Current password is incorrect");
    }

    @Test
    void newPasswordMustBeDifferentAndLongEnough() {

        String token = login(email, PASSWORD).getBody().accessToken();

        ResponseEntity<Map> same = changePassword(token, PASSWORD, PASSWORD, Map.class);
        ResponseEntity<Map> tooShort = changePassword(token, PASSWORD, "short", Map.class);

        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldErrors(same)).containsKey("newPassword");
        assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldErrors(tooShort)).containsKey("newPassword");
    }

    @Test
    void changingThePasswordSignsOutOtherDevicesButKeepsThisOne() throws InterruptedException {

        ResponseEntity<AuthResponse> otherDevice = login(email, PASSWORD);
        ResponseEntity<AuthResponse> thisDevice = login(email, PASSWORD);
        waitForNextSecond(); // the cutoff has whole-second precision, like JWT "iat"

        ResponseEntity<AuthResponse> changed =
                changePassword(thisDevice.getBody().accessToken(), PASSWORD, NEW_PASSWORD, AuthResponse.class);

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).startsWith(COOKIE + "=");

        // The other device: its unexpired access token and its refresh cookie are both dead.
        ResponseEntity<Map> otherMe = rest.exchange("/api/auth/me", HttpMethod.GET,
                bearer(otherDevice.getBody().accessToken()), Map.class);
        assertThat(otherMe.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(otherMe.getBody()).containsEntry("message", "Session has ended, please sign in again");
        assertThat(refresh(cookie(otherDevice)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // This device's old token is dead too, but the new session works.
        assertThat(rest.exchange("/api/auth/me", HttpMethod.GET, bearer(thisDevice.getBody().accessToken()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange("/api/auth/me", HttpMethod.GET, bearer(changed.getBody().accessToken()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh(cookie(changed)).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Only the new password works from now on.
        assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------- sign out everywhere ----------

    @Test
    void signOutEverywhereEndsEverySession() throws InterruptedException {

        ResponseEntity<AuthResponse> otherDevice = login(email, PASSWORD);
        ResponseEntity<AuthResponse> thisDevice = login(email, PASSWORD);
        waitForNextSecond();

        ResponseEntity<Void> response = rest.exchange("/api/auth/logout-all", HttpMethod.POST,
                bearer(thisDevice.getBody().accessToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        for (ResponseEntity<AuthResponse> device : List.of(otherDevice, thisDevice)) {
            assertThat(rest.exchange("/api/auth/me", HttpMethod.GET, bearer(device.getBody().accessToken()), Map.class)
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(refresh(cookie(device)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // Signing in again works: the cutoff only affects older tokens.
        String fresh = login(email, PASSWORD).getBody().accessToken();
        assertThat(rest.exchange("/api/auth/me", HttpMethod.GET, bearer(fresh), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void signOutEverywhereNeedsAToken() {

        ResponseEntity<Map> response = rest.exchange("/api/auth/logout-all", HttpMethod.POST, HttpEntity.EMPTY, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------- helpers ----------

    private static void waitForNextSecond() throws InterruptedException {
        Thread.sleep(1_000 - (System.currentTimeMillis() % 1_000) + 50);
    }

    private ResponseEntity<AuthResponse> login(String email, String password) {
        return rest.postForEntity("/api/auth/login", Map.of("email", email, "password", password), AuthResponse.class);
    }

    private ResponseEntity<Map> refresh(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, COOKIE + "=" + cookie);
        return rest.exchange("/api/auth/refresh", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }

    private <T> ResponseEntity<T> updateProfile(String token, String name, String email, String currentPassword,
                                                Class<T> type) {
        Map<String, String> body = new HashMap<>(Map.of("name", name, "email", email));
        if (currentPassword != null) {
            body.put("currentPassword", currentPassword);
        }
        return rest.exchange("/api/profile", HttpMethod.PUT, new HttpEntity<>(body, bearerHeaders(token)), type);
    }

    private <T> ResponseEntity<T> changePassword(String token, String current, String next, Class<T> type) {
        return rest.exchange("/api/profile/password", HttpMethod.PUT,
                new HttpEntity<>(Map.of("currentPassword", current, "newPassword", next), bearerHeaders(token)), type);
    }

    private static Map<String, String> fieldErrors(ResponseEntity<Map> response) {
        List<Map<String, String>> errors = (List<Map<String, String>>) response.getBody().get("errors");
        Map<String, String> byField = new HashMap<>();
        errors.forEach(error -> byField.put(error.get("field"), error.get("message")));
        return byField;
    }

    private static String cookie(ResponseEntity<?> response) {
        return HttpCookie.parse(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).getFirst().getValue();
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpEntity<Void> bearer(String token) {
        return new HttpEntity<>(bearerHeaders(token));
    }
}
