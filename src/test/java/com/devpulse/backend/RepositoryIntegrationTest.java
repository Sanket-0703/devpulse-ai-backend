package com.devpulse.backend;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.github.RepositoryResponse;
import com.devpulse.backend.dto.github.SyncStatusResponse;
import com.devpulse.backend.entity.SyncStatus;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Level 5: repositories come from the DevPulse app installation. Sync runs
 * in the background with an installation token (app JWT signed with a real
 * RSA key generated for the test); the list, detail and review toggle are
 * scoped to the signed-in user. GitHub is WireMock.
 */
class RepositoryIntegrationTest extends AbstractIntegrationTest {

    private static final String COOKIE = "devpulse_refresh";
    private static final long APP_ID = 424242;

    static final WireMockServer GITHUB = new WireMockServer(options().dynamicPort());
    static final KeyPair APP_KEY = generateKey();
    static final Path APP_KEY_FILE = writePkcs8Pem(APP_KEY);

    static {
        GITHUB.start();
    }

    @DynamicPropertySource
    static void fakeGitHub(DynamicPropertyRegistry registry) {
        registry.add("github.web-url", GITHUB::baseUrl);
        registry.add("github.api-url", GITHUB::baseUrl);
        registry.add("github.private-key-path", APP_KEY_FILE::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private final HttpClient browser = HttpClient.newHttpClient();

    // ---------- sync ----------

    @Test
    void connectingGitHubSyncsTheInstalledRepositoriesInTheBackground() throws Exception {

        Identity ada = Identity.random();
        stubIdentity(ada, repo(ada.repoId(1), "ada/backend", "Spring Boot API", true, "Java", "2026-10-20T10:00:00Z"),
                repo(ada.repoId(2), "ada/frontend", null, false, "TypeScript", "2026-10-24T10:00:00Z"));

        String token = signInWithGitHub(ada);
        SyncStatusResponse status = awaitSync(token);

        assertThat(status.status()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(status.repositoryCount()).isEqualTo(2);
        assertThat(status.lastSyncedAt()).isNotNull();

        List<RepositoryResponse> repos = list(token, "").content();
        assertThat(repos).extracting(RepositoryResponse::fullName).containsExactly("ada/backend", "ada/frontend");
        RepositoryResponse backend = repos.getFirst();
        assertThat(backend.isPrivate()).isTrue();
        assertThat(backend.language()).isEqualTo("Java");
        assertThat(backend.pushedAt()).isEqualTo(Instant.parse("2026-10-20T10:00:00Z"));
        assertThat(backend.reviewEnabled()).isFalse(); // opt-in

        // The installation token was requested with an app JWT signed by the app's private key.
        LoggedRequest tokenRequest = GITHUB.findAll(
                postRequestedFor(urlEqualTo("/app/installations/" + ada.installationId() + "/access_tokens"))).getFirst();
        String jwt = tokenRequest.getHeader("Authorization").substring("Bearer ".length());
        Claims claims = Jwts.parser().verifyWith(APP_KEY.getPublic()).build().parseSignedClaims(jwt).getPayload();
        assertThat(claims.getIssuer()).isEqualTo(String.valueOf(APP_ID));
    }

    @Test
    void theInstallationTokenIsCachedBetweenSyncs() throws Exception {

        Identity ada = Identity.random();
        stubIdentity(ada, repo(ada.repoId(1), "ada/backend", null, false, "Java", null));
        String token = signInWithGitHub(ada);
        awaitSync(token);

        assertThat(rest.exchange("/api/github/repositories/sync", HttpMethod.POST, bearer(token), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitSync(token);

        // Two syncs, one token request: the second sync reused the cached token.
        GITHUB.verify(1, postRequestedFor(urlEqualTo("/app/installations/" + ada.installationId() + "/access_tokens")));
    }

    @Test
    void manualSyncIsLimitedToOnceAMinute() throws Exception {

        Identity ada = Identity.random();
        stubIdentity(ada, repo(ada.repoId(1), "ada/backend", null, false, "Java", null));
        String token = signInWithGitHub(ada);
        awaitSync(token);

        // JDK HttpClient: it reports the 429 as is. (TestRestTemplate's Apache
        // client would honour Retry-After and silently retry a minute later.)
        assertThat(postSync(token).statusCode()).isEqualTo(202);
        HttpResponse<String> second = postSync(token);

        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(second.headers().firstValue(HttpHeaders.RETRY_AFTER)).isPresent();
        assertThat(second.body()).contains("You can sync once a minute");
    }

    @Test
    void resyncRemovesRepositoriesNoLongerInstalledAndKeepsTheReviewSetting() throws Exception {

        Identity ada = Identity.random();
        stubIdentity(ada, repo(ada.repoId(1), "ada/backend", "old description", false, "Java", null),
                repo(ada.repoId(2), "ada/frontend", null, false, "TypeScript", null));
        String token = signInWithGitHub(ada);
        awaitSync(token);
        RepositoryResponse backend = list(token, "?q=backend").content().getFirst();
        setReview(token, backend.id(), true);

        // The user removed ada/frontend from the app on GitHub, and edited ada/backend.
        stubInstallationRepositories(ada, repo(ada.repoId(1), "ada/backend", "new description", false, "Java", null));
        rest.exchange("/api/github/repositories/sync", HttpMethod.POST, bearer(token), Map.class);
        awaitSync(token);

        List<RepositoryResponse> repos = list(token, "").content();
        assertThat(repos).extracting(RepositoryResponse::fullName).containsExactly("ada/backend");
        assertThat(repos.getFirst().description()).isEqualTo("new description");
        assertThat(repos.getFirst().reviewEnabled()).isTrue(); // the user's choice survives syncs
        assertThat(repos.getFirst().id()).isEqualTo(backend.id());
    }

    @Test
    void aFailedSyncIsReportedWithAReason() throws Exception {

        Identity ada = Identity.random();
        stubIdentity(ada);
        GITHUB.stubFor(post(urlEqualTo("/app/installations/" + ada.installationId() + "/access_tokens"))
                .willReturn(aResponse().withStatus(500)));

        String token = signInWithGitHub(ada);
        SyncStatusResponse status = awaitSync(token);

        assertThat(status.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(status.error()).isEqualTo("GitHub didn't respond as expected. Please try again in a moment.");
    }

    @Test
    void syncNeedsAConnectedGitHubAccount() {

        String token = registerAndLogin();

        SyncStatusResponse status = rest.exchange("/api/github/repositories/sync", HttpMethod.GET, bearer(token),
                SyncStatusResponse.class).getBody();
        ResponseEntity<Map> sync = rest.exchange("/api/github/repositories/sync", HttpMethod.POST, bearer(token), Map.class);

        assertThat(status.connected()).isFalse();
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) sync.getBody().get("message")).startsWith("Connect GitHub first");
    }

    @Test
    void theSameRepositoryCanBelongToTwoDevPulseUsers() throws Exception {

        // An organization repo both people installed the app on.
        long sharedRepoId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        Identity ada = Identity.random();
        Identity grace = Identity.random();
        stubIdentity(ada, repo(sharedRepoId, "org/shared", null, false, "Go", null));
        stubIdentity(grace, repo(sharedRepoId, "org/shared", null, false, "Go", null));

        String adaToken = signInWithGitHub(ada);
        awaitSync(adaToken);
        String graceToken = signInWithGitHub(grace);
        awaitSync(graceToken);

        RepositoryResponse adas = list(adaToken, "").content().getFirst();
        RepositoryResponse graces = list(graceToken, "").content().getFirst();
        assertThat(adas.githubId()).isEqualTo(graces.githubId());
        assertThat(adas.id()).isNotEqualTo(graces.id()); // separate rows, separate review settings
    }

    // ---------- list, detail, toggle ----------

    @Test
    void listPagesSearchesFiltersAndSorts() throws Exception {

        Identity ada = Identity.random();
        stubIdentity(ada,
                repo(ada.repoId(1), "ada/api-gateway", "Edge proxy", false, "Go", "2026-10-01T00:00:00Z"),
                repo(ada.repoId(2), "ada/backend", "Spring Boot API", false, "Java", "2026-10-24T00:00:00Z"),
                repo(ada.repoId(3), "ada/frontend", "React app (50% done)", false, "TypeScript", null));
        String token = signInWithGitHub(ada);
        awaitSync(token);

        var firstPage = list(token, "?size=2&page=0");
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.content()).extracting(RepositoryResponse::fullName)
                .containsExactly("ada/api-gateway", "ada/backend");
        assertThat(list(token, "?size=2&page=1").content()).extracting(RepositoryResponse::fullName)
                .containsExactly("ada/frontend");

        // Search matches owner/name and description, case-insensitively; % is literal.
        assertThat(list(token, "?q=API").content()).extracting(RepositoryResponse::fullName)
                .containsExactly("ada/api-gateway", "ada/backend");
        assertThat(list(token, "?q={q}", "50%").content()).extracting(RepositoryResponse::fullName)
                .containsExactly("ada/frontend");

        // Most recently pushed first; never-pushed last.
        assertThat(list(token, "?sort=PUSHED").content()).extracting(RepositoryResponse::fullName)
                .containsExactly("ada/backend", "ada/api-gateway", "ada/frontend");

        RepositoryResponse backend = list(token, "?q=backend").content().getFirst();
        setReview(token, backend.id(), true);
        assertThat(list(token, "?reviewEnabled=true").content()).extracting(RepositoryResponse::fullName)
                .containsExactly("ada/backend");
        assertThat(list(token, "?reviewEnabled=false").totalElements()).isEqualTo(2);
    }

    @Test
    void detailAndToggleWorkOnlyForYourOwnRepositories() throws Exception {

        Identity ada = Identity.random();
        stubIdentity(ada, repo(ada.repoId(1), "ada/backend", null, false, "Java", null));
        String adaToken = signInWithGitHub(ada);
        awaitSync(adaToken);
        Long repoId = list(adaToken, "").content().getFirst().id();

        ResponseEntity<RepositoryResponse> mine = rest.exchange("/api/github/repositories/" + repoId, HttpMethod.GET,
                bearer(adaToken), RepositoryResponse.class);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setReview(adaToken, repoId, true).reviewEnabled()).isTrue();

        String otherUser = registerAndLogin();
        assertThat(rest.exchange("/api/github/repositories/" + repoId, HttpMethod.GET, bearer(otherUser), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange("/api/github/repositories/" + repoId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("reviewEnabled", false), bearerHeaders(otherUser)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(list(otherUser, "").totalElements()).isZero();
    }

    @Test
    void theToggleNeedsAValue() throws Exception {

        Identity ada = Identity.random();
        stubIdentity(ada, repo(ada.repoId(1), "ada/backend", null, false, "Java", null));
        String token = signInWithGitHub(ada);
        awaitSync(token);
        Long repoId = list(token, "").content().getFirst().id();

        ResponseEntity<Map> response = rest.exchange("/api/github/repositories/" + repoId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of(), bearerHeaders(token)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- fake GitHub ----------

    record Identity(long githubUserId, long installationId, String code) {

        static Identity random() {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            return new Identity(random.nextLong(1, 1_000_000_000L), random.nextLong(1, 1_000_000_000L),
                    "code-" + UUID.randomUUID());
        }

        String userToken() {
            return "ghu_" + githubUserId;
        }

        String installationToken() {
            return "ghs_" + installationId;
        }

        long repoId(int n) {
            return installationId * 10 + n;
        }
    }

    private void stubIdentity(Identity identity, String... repos) {

        GITHUB.stubFor(post(urlEqualTo("/login/oauth/access_token"))
                .withRequestBody(containing("code=" + identity.code()))
                .willReturn(okJson("""
                        {"access_token":"%s","expires_in":28800,"refresh_token":"ghr_x",
                         "refresh_token_expires_in":15897600,"token_type":"bearer"}
                        """.formatted(identity.userToken()))));

        GITHUB.stubFor(get(urlEqualTo("/user")).withHeader("Authorization", equalTo("Bearer " + identity.userToken()))
                .willReturn(okJson("""
                        {"id":%d,"login":"user-%d","name":"User %d","avatar_url":null}
                        """.formatted(identity.githubUserId(), identity.githubUserId(), identity.githubUserId()))));

        GITHUB.stubFor(get(urlEqualTo("/user/emails"))
                .withHeader("Authorization", equalTo("Bearer " + identity.userToken()))
                .willReturn(okJson("""
                        [{"email":"user-%d@example.com","primary":true,"verified":true}]
                        """.formatted(identity.githubUserId()))));

        GITHUB.stubFor(get(urlEqualTo("/user/installations"))
                .withHeader("Authorization", equalTo("Bearer " + identity.userToken()))
                .willReturn(okJson("""
                        {"total_count":1,"installations":[{"id":%d,"app_id":%d,"account":{"id":1,"login":"x"}}]}
                        """.formatted(identity.installationId(), APP_ID))));

        GITHUB.stubFor(post(urlEqualTo("/app/installations/" + identity.installationId() + "/access_tokens"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"token":"%s","expires_at":"%s","repository_selection":"selected"}
                                """.formatted(identity.installationToken(), Instant.now().plus(Duration.ofHours(1))))));

        stubInstallationRepositories(identity, repos);
    }

    private void stubInstallationRepositories(Identity identity, String... repos) {
        GITHUB.stubFor(get(urlPathEqualTo("/installation/repositories"))
                .withHeader("Authorization", equalTo("Bearer " + identity.installationToken()))
                .willReturn(okJson("""
                        {"total_count":%d,"repository_selection":"selected","repositories":[%s]}
                        """.formatted(repos.length, String.join(",", repos)))));
    }

    private static String repo(long id, String fullName, String description, boolean isPrivate, String language,
                               String pushedAt) {
        String name = fullName.substring(fullName.indexOf('/') + 1);
        return """
                {"id":%d,"name":"%s","full_name":"%s","description":%s,"private":%s,"fork":false,"archived":false,
                 "html_url":"https://github.com/%s","default_branch":"main","language":"%s","pushed_at":%s}
                """.formatted(id, name, fullName, description == null ? "null" : "\"" + description + "\"",
                isPrivate, fullName, language, pushedAt == null ? "null" : "\"" + pushedAt + "\"");
    }

    // ---------- helpers ----------

    /** Runs the real "Sign in with GitHub" flow and returns an access token. */
    private String signInWithGitHub(Identity identity) throws IOException, InterruptedException {
        HttpResponse<Void> start = browserGet("/api/auth/github");
        String state = UriComponentsBuilder.fromUri(URI.create(start.headers().firstValue("Location").orElseThrow()))
                .build().getQueryParams().getFirst("state");
        HttpResponse<Void> callback = browserGet("/api/github/oauth/callback?code=" + identity.code() + "&state=" + state);
        String setCookie = callback.headers().allValues("Set-Cookie").stream()
                .filter(v -> v.startsWith(COOKIE + "=")).findFirst().orElseThrow();

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, COOKIE + "=" + HttpCookie.parse(setCookie).getFirst().getValue());
        return rest.exchange("/api/auth/refresh", HttpMethod.POST, new HttpEntity<>(headers), AuthResponse.class)
                .getBody().accessToken();
    }

    private SyncStatusResponse awaitSync(String token) {
        return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> rest.exchange("/api/github/repositories/sync", HttpMethod.GET, bearer(token),
                                SyncStatusResponse.class).getBody(),
                        status -> status.status() == SyncStatus.SUCCEEDED || status.status() == SyncStatus.FAILED);
    }

    /** {@code query} may contain {placeholders}, filled from {@code values} and encoded once. */
    private Page list(String token, String query, Object... values) {
        return rest.exchange("/api/github/repositories" + query, HttpMethod.GET, bearer(token),
                new ParameterizedTypeReference<Page>() {
                }, values).getBody();
    }

    private HttpResponse<String> postSync(String token) throws IOException, InterruptedException {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/github/repositories/sync"))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    record Page(List<RepositoryResponse> content, int page, int size, long totalElements, int totalPages) {
    }

    private RepositoryResponse setReview(String token, Long repoId, boolean enabled) {
        return rest.exchange("/api/github/repositories/" + repoId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("reviewEnabled", enabled), bearerHeaders(token)), RepositoryResponse.class)
                .getBody();
    }

    private String registerAndLogin() {
        String email = "pw-" + UUID.randomUUID() + "@example.com";
        rest.postForEntity("/api/auth/register",
                Map.of("name", "Password User", "email", email, "password", "Passw0rd!23"), Map.class);
        return rest.postForEntity("/api/auth/login", Map.of("email", email, "password", "Passw0rd!23"),
                AuthResponse.class).getBody().accessToken();
    }

    private HttpResponse<Void> browserGet(String path) throws IOException, InterruptedException {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpEntity<Void> bearer(String token) {
        return new HttpEntity<>(bearerHeaders(token));
    }

    private static KeyPair generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePkcs8Pem(KeyPair keyPair) {
        try {
            Path file = Files.createTempFile("devpulse-test-app-key", ".pem");
            file.toFile().deleteOnExit();
            // Markers built from parts so secret scanners don't mistake this code for a committed key.
            String type = "PRIVATE" + " KEY";
            Files.writeString(file, "-----BEGIN " + type + "-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                    + "\n-----END " + type + "-----\n");
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
