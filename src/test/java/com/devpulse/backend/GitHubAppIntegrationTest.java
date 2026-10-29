package com.devpulse.backend;

import com.devpulse.backend.dto.AuthResponse;
import com.devpulse.backend.dto.github.SyncStatusResponse;
import com.devpulse.backend.entity.SyncStatus;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.awaitility.Awaitility.await;

/**
 * Base for tests that need GitHub: a WireMock "GitHub" plus a real RSA key
 * for the GitHub App, and helpers to sign in with GitHub and sync
 * repositories. Every subclass shares one Spring context (same properties).
 */
abstract class GitHubAppIntegrationTest extends AbstractIntegrationTest {

    static final String COOKIE = "devpulse_refresh";
    static final long APP_ID = 424242;

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
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    protected final HttpClient browser = HttpClient.newHttpClient();

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

    protected void stubIdentity(Identity identity, String... repos) {

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

    protected void stubInstallationRepositories(Identity identity, String... repos) {
        GITHUB.stubFor(get(urlPathEqualTo("/installation/repositories"))
                .withHeader("Authorization", equalTo("Bearer " + identity.installationToken()))
                .willReturn(okJson("""
                        {"total_count":%d,"repository_selection":"selected","repositories":[%s]}
                        """.formatted(repos.length, String.join(",", repos)))));
    }

    protected static String repo(long id, String fullName, String description, boolean isPrivate, String language,
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
    protected String signInWithGitHub(Identity identity) throws IOException, InterruptedException {
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

    protected SyncStatusResponse awaitSync(String token) {
        return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> rest.exchange("/api/github/repositories/sync", HttpMethod.GET, bearer(token),
                                SyncStatusResponse.class).getBody(),
                        status -> status.status() == SyncStatus.SUCCEEDED || status.status() == SyncStatus.FAILED);
    }

    protected String registerAndLogin() {
        String email = "pw-" + UUID.randomUUID() + "@example.com";
        rest.postForEntity("/api/auth/register",
                Map.of("name", "Password User", "email", email, "password", "Passw0rd!23"), Map.class);
        return rest.postForEntity("/api/auth/login", Map.of("email", email, "password", "Passw0rd!23"),
                AuthResponse.class).getBody().accessToken();
    }

    protected HttpResponse<Void> browserGet(String path) throws IOException, InterruptedException {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    protected static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    protected static HttpEntity<Void> bearer(String token) {
        return new HttpEntity<>(bearerHeaders(token));
    }

    protected static KeyPair generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static Path writePkcs8Pem(KeyPair keyPair) {
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
