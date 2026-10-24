package com.devpulse.backend;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.github.GitHubConnectionResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 4: every GitHub flow end to end — real app, PostgreSQL and Redis,
 * with GitHub itself replaced by WireMock (a local HTTP server returning the
 * JSON GitHub would). Browser steps use a client that doesn't follow
 * redirects, so each 302 can be inspected like the browser would see it.
 */
class GitHubConnectionIntegrationTest extends AbstractIntegrationTest {

    private static final String COOKIE = "devpulse_refresh";
    private static final long APP_ID = 424242; // github.app-id in application-test.properties
    private static final String GITHUB_TOKEN = "ghu_testAccessToken123";

    static final WireMockServer GITHUB = new WireMockServer(options().dynamicPort());

    static {
        GITHUB.start();
    }

    @DynamicPropertySource
    static void pointAtFakeGitHub(DynamicPropertyRegistry registry) {
        registry.add("github.web-url", GITHUB::baseUrl);
        registry.add("github.api-url", GITHUB::baseUrl);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    // JDK HttpClient never follows redirects by default: we want to see the 302s.
    private final HttpClient browser = HttpClient.newHttpClient();

    private long githubUserId;
    private String githubEmail;

    @BeforeEach
    void freshGitHubIdentity() {
        GITHUB.resetAll();
        githubUserId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        githubEmail = "gh-" + githubUserId + "@example.com";
        stubGitHub(githubEmail, true);
    }

    // ---------- Sign in with GitHub ----------

    @Test
    void signInWithGitHubCreatesAnAccountAndSignsIn() throws Exception {

        HttpResponse<Void> start = browserGet("/api/auth/github");
        assertThat(start.statusCode()).isEqualTo(302);
        URI authorize = URI.create(location(start));
        assertThat(authorize.toString()).startsWith(GITHUB.baseUrl() + "/login/oauth/authorize");
        assertThat(query(authorize, "client_id")).isEqualTo("test-client-id");

        HttpResponse<Void> callback = callback(query(authorize, "state"));

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(location(callback)).isEqualTo("http://localhost:5173/github/callback?result=signed-in&new=true");
        String cookie = refreshCookie(callback);

        // The frontend restores the session from the cookie, like after a reload.
        AuthResponse session = refresh(cookie).getBody();
        assertThat(session.user().email()).isEqualTo(githubEmail);
        assertThat(session.user().name()).isEqualTo("Ada GitHub");
        assertThat(session.user().hasPassword()).isFalse();

        GitHubConnectionResponse status = status(session.accessToken());
        assertThat(status.connected()).isTrue();
        assertThat(status.username()).isEqualTo("octo-" + githubUserId);
        assertThat(status.installed()).isTrue();
        assertThat(status.manageRepositoriesUrl()).isEqualTo(GITHUB.baseUrl() + "/settings/installations/555");
    }

    @Test
    void githubTokensAreEncryptedInTheDatabase() throws Exception {

        callback(startState("/api/auth/github"));

        Map<String, Object> row = jdbc.queryForMap(
                "select access_token, refresh_token, installation_id from github_accounts where github_id = ?",
                githubUserId);
        assertThat((String) row.get("access_token")).startsWith("v1:").doesNotContain(GITHUB_TOKEN);
        assertThat((String) row.get("refresh_token")).startsWith("v1:").doesNotContain("ghr_");
        // Only our app's installation (app_id 424242), not another app's.
        assertThat(row.get("installation_id")).isEqualTo(555L);
    }

    @Test
    void signingInAgainUsesTheSameAccount() throws Exception {

        callback(startState("/api/auth/github"));
        HttpResponse<Void> second = callback(startState("/api/auth/github"));

        assertThat(location(second)).endsWith("result=signed-in&new=false");
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, githubEmail))
                .isEqualTo(1);
    }

    @Test
    void signInNeverTakesOverAnExistingAccountWithTheSameEmail() throws Exception {

        // Someone registered this email with a password first (emails aren't verified).
        rest.postForEntity("/api/auth/register",
                Map.of("name", "Someone", "email", githubEmail, "password", "Passw0rd!23"), Map.class);

        HttpResponse<Void> callback = callback(startState("/api/auth/github"));

        assertThat(location(callback)).endsWith("result=error&reason=email_exists");
        assertThat(callback.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from github_accounts where github_id = ?",
                Integer.class, githubUserId)).isZero();
    }

    @Test
    void signInNeedsAVerifiedEmail() throws Exception {

        stubGitHub(githubEmail, false);

        HttpResponse<Void> callback = callback(startState("/api/auth/github"));

        assertThat(location(callback)).endsWith("result=error&reason=no_verified_email");
    }

    @Test
    void aGitHubOnlyAccountCanSetItsFirstPasswordAndThenUseIt() throws Exception {

        String cookie = refreshCookie(callback(startState("/api/auth/github")));
        String token = refresh(cookie).getBody().accessToken();

        ResponseEntity<AuthResponse> setPassword = rest.exchange("/api/profile/password", HttpMethod.PUT,
                new HttpEntity<>(Map.of("newPassword", "First-Passw0rd!"), bearer(token)), AuthResponse.class);

        assertThat(setPassword.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setPassword.getBody().user().hasPassword()).isTrue();
        assertThat(rest.postForEntity("/api/auth/login",
                Map.of("email", githubEmail, "password", "First-Passw0rd!"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aGitHubOnlyAccountCannotLogInWithAnyPassword() throws Exception {

        callback(startState("/api/auth/github"));

        ResponseEntity<Map> login = rest.postForEntity("/api/auth/login",
                Map.of("email", githubEmail, "password", "anything-at-all"), Map.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------- bad callbacks ----------

    @Test
    void unknownOrReusedStateIsRejected() throws Exception {

        assertThat(location(callback("made-up-state"))).endsWith("result=error&reason=invalid_state");

        String state = startState("/api/auth/github");
        callback(state);
        assertThat(location(callback(state))).endsWith("result=error&reason=invalid_state");
    }

    @Test
    void cancellingOnGitHubIsReportedAsSuch() throws Exception {

        HttpResponse<Void> callback = browserGet("/api/github/oauth/callback?error=access_denied&state="
                + startState("/api/auth/github"));

        assertThat(location(callback)).endsWith("result=error&reason=access_denied");
    }

    @Test
    void aCodeGitHubRejectsIsAnError() throws Exception {

        // GitHub reports a bad code with HTTP 200 and an "error" field.
        GITHUB.stubFor(post(urlEqualTo("/login/oauth/access_token"))
                .willReturn(okJson("{\"error\":\"bad_verification_code\"}")));

        assertThat(location(callback(startState("/api/auth/github")))).endsWith("result=error&reason=github_error");
    }

    // ---------- Connect GitHub (signed in with a password) ----------

    @Test
    void connectLinksGitHubToTheSignedInUser() throws Exception {

        String token = registerAndLogin();
        assertThat(status(token).connected()).isFalse();

        String url = rest.exchange("/api/github/connect", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class)
                .getBody().get("url").toString();
        HttpResponse<Void> callback = callback(query(URI.create(url), "state"));

        assertThat(location(callback)).endsWith("result=connected");
        assertThat(callback.headers().firstValue("Set-Cookie")).isEmpty(); // already signed in
        assertThat(status(token).username()).isEqualTo("octo-" + githubUserId);
    }

    @Test
    void installStartsGitHubsInstallPageBoundToTheUser() {

        String token = registerAndLogin();

        String url = rest.exchange("/api/github/install", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class)
                .getBody().get("url").toString();

        assertThat(url).startsWith(GITHUB.baseUrl() + "/apps/devpulse-test/installations/new?state=");
    }

    @Test
    void aGitHubAccountCanBelongToOnlyOneUser() throws Exception {

        callback(startState("/api/auth/github")); // someone signed in with this GitHub account

        String otherUser = registerAndLogin();
        String url = rest.exchange("/api/github/connect", HttpMethod.POST, new HttpEntity<>(bearer(otherUser)), Map.class)
                .getBody().get("url").toString();

        assertThat(location(callback(query(URI.create(url), "state"))))
                .endsWith("result=error&reason=github_account_in_use");
    }

    @Test
    void githubCanSendUsersBackAfterChangingRepositoriesWithoutOurState() throws Exception {

        // GitHub's "Redirect on update": no state, only setup_action.
        HttpResponse<Void> unknown = browserGet("/api/github/oauth/callback?code=c&setup_action=update");
        assertThat(location(unknown)).endsWith("result=error&reason=not_connected");

        callback(startState("/api/auth/github"));
        HttpResponse<Void> known = browserGet("/api/github/oauth/callback?code=c&setup_action=update");
        assertThat(location(known)).endsWith("result=connected");
        assertThat(known.headers().firstValue("Set-Cookie")).isEmpty(); // never a sign-in without state
    }

    // ---------- disconnect ----------

    @Test
    void disconnectRevokesTheAuthorizationOnGitHubAndRemovesTheLink() throws Exception {

        String token = registerAndLogin();
        String url = rest.exchange("/api/github/connect", HttpMethod.POST, new HttpEntity<>(bearer(token)), Map.class)
                .getBody().get("url").toString();
        callback(query(URI.create(url), "state"));

        ResponseEntity<Void> response = rest.exchange("/api/github/connection", HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(status(token).connected()).isFalse();
        GITHUB.verify(deleteRequestedFor(urlEqualTo("/applications/test-client-id/grant"))
                .withBasicAuth(new com.github.tomakehurst.wiremock.client.BasicCredentials(
                        "test-client-id", "test-client-secret"))
                .withRequestBody(equalToJson("{\"access_token\":\"" + GITHUB_TOKEN + "\"}")));
    }

    @Test
    void disconnectIsRefusedWhenGitHubIsTheOnlyWayToSignIn() throws Exception {

        String token = refresh(refreshCookie(callback(startState("/api/auth/github")))).getBody().accessToken();

        ResponseEntity<Map> response = rest.exchange("/api/github/connection", HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(token).connected()).isTrue();
    }

    @Test
    void connectionEndpointsNeedAToken() {

        assertThat(rest.getForEntity("/api/github/connection", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.postForEntity("/api/github/connect", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------- fake GitHub ----------

    private void stubGitHub(String email, boolean verified) {

        GITHUB.stubFor(post(urlEqualTo("/login/oauth/access_token")).willReturn(okJson("""
                {"access_token":"%s","expires_in":28800,"refresh_token":"ghr_testRefresh",
                 "refresh_token_expires_in":15897600,"token_type":"bearer","scope":""}
                """.formatted(GITHUB_TOKEN))));

        GITHUB.stubFor(get(urlEqualTo("/user")).withHeader("Authorization", equalTo("Bearer " + GITHUB_TOKEN))
                .willReturn(okJson("""
                        {"id":%d,"login":"octo-%d","name":"Ada GitHub","avatar_url":"https://avatars.example/%d"}
                        """.formatted(githubUserId, githubUserId, githubUserId))));

        GITHUB.stubFor(get(urlEqualTo("/user/emails")).willReturn(okJson("""
                [{"email":"secondary@example.com","primary":false,"verified":true},
                 {"email":"%s","primary":true,"verified":%s}]
                """.formatted(email.toUpperCase(), verified))));
        if (!verified) {
            GITHUB.stubFor(get(urlEqualTo("/user/emails")).willReturn(okJson("""
                    [{"email":"%s","primary":true,"verified":false}]
                    """.formatted(email))));
        }

        GITHUB.stubFor(get(urlEqualTo("/user/installations")).willReturn(okJson("""
                {"total_count":2,"installations":[
                  {"id":999,"app_id":1,"account":{"id":1,"login":"other-app"}},
                  {"id":555,"app_id":%d,"account":{"id":%d,"login":"octo"}}]}
                """.formatted(APP_ID, githubUserId))));

        GITHUB.stubFor(delete(urlEqualTo("/applications/test-client-id/grant"))
                .willReturn(aResponse().withStatus(204)));
    }

    // ---------- helpers ----------

    private HttpResponse<Void> browserGet(String path) throws IOException, InterruptedException {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private String startState(String path) throws IOException, InterruptedException {
        return query(URI.create(location(browserGet(path))), "state");
    }

    private HttpResponse<Void> callback(String state) throws IOException, InterruptedException {
        return browserGet("/api/github/oauth/callback?code=one-time-code&state=" + state);
    }

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("Location").orElseThrow();
    }

    private static String query(URI uri, String name) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
    }

    private static String refreshCookie(HttpResponse<?> response) {
        String header = response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(COOKIE + "="))
                .findFirst().orElseThrow();
        return HttpCookie.parse(header).getFirst().getValue();
    }

    private ResponseEntity<AuthResponse> refresh(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, COOKIE + "=" + cookie);
        return rest.exchange("/api/auth/refresh", HttpMethod.POST, new HttpEntity<>(headers), AuthResponse.class);
    }

    private GitHubConnectionResponse status(String token) {
        return rest.exchange("/api/github/connection", HttpMethod.GET, new HttpEntity<>(bearer(token)),
                GitHubConnectionResponse.class).getBody();
    }

    private String registerAndLogin() {
        String email = "pw-" + UUID.randomUUID() + "@example.com";
        rest.postForEntity("/api/auth/register",
                Map.of("name", "Password User", "email", email, "password", "Passw0rd!23"), Map.class);
        return rest.postForEntity("/api/auth/login", Map.of("email", email, "password", "Passw0rd!23"),
                AuthResponse.class).getBody().accessToken();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
