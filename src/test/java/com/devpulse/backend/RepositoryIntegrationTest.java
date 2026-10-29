package com.devpulse.backend;

import com.devpulse.backend.dto.github.RepositoryResponse;
import com.devpulse.backend.dto.github.SyncStatusResponse;
import com.devpulse.backend.entity.SyncStatus;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 5: repositories come from the DevPulse app installation. Sync runs
 * in the background with an installation token (app JWT signed with a real
 * RSA key generated for the test); the list, detail and review toggle are
 * scoped to the signed-in user. GitHub is WireMock.
 */
class RepositoryIntegrationTest extends GitHubAppIntegrationTest {

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

    // ---------- helpers ----------

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
}
