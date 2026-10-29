package com.devpulse.backend;

import com.devpulse.backend.dto.github.PullRequestDetailResponse;
import com.devpulse.backend.dto.github.PullRequestFilesResponse;
import com.devpulse.backend.dto.github.PullRequestResponse;
import com.devpulse.backend.dto.github.PullRequestSyncResponse;
import com.devpulse.backend.dto.github.RepositoryResponse;
import com.devpulse.backend.entity.PullRequestState;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 6: a repository's pull requests are synced from GitHub with the
 * installation token, listed from the database, and their changed files
 * come live from GitHub through a Redis cache. GitHub is WireMock.
 */
class PullRequestIntegrationTest extends GitHubAppIntegrationTest {

    // ---------- sync + list ----------

    @Test
    void syncStoresThePullRequestsAndTheListFiltersAndSearchesThem() throws Exception {

        Setup ada = signedInWithOneRepo();
        stubPulls(ada, 1,
                pr(4, "Add login page", "open", null, "2026-10-25T10:00:00Z", "grace", true),
                pr(3, "Fix NPE in sync", "closed", "2026-10-24T10:00:00Z", "2026-10-24T10:00:00Z", "ada", false),
                pr(2, "Try a new parser", "closed", null, "2026-10-23T10:00:00Z", "ada", false),
                pr(1, "Initial commit", "closed", "2026-10-01T10:00:00Z", "2026-10-01T10:00:00Z", "ada", false));

        PullRequestSyncResponse sync = sync(ada);

        assertThat(sync.updated()).isEqualTo(4);
        assertThat(sync.total()).isEqualTo(4);
        assertThat(repository(ada).pullRequestsSyncedAt()).isEqualTo(sync.syncedAt());

        // Most recent activity first; merged is its own state.
        List<PullRequestResponse> all = list(ada, "").content();
        assertThat(all).extracting(PullRequestResponse::number).containsExactly(4, 3, 2, 1);
        assertThat(all).extracting(PullRequestResponse::state).containsExactly(
                PullRequestState.OPEN, PullRequestState.MERGED, PullRequestState.CLOSED, PullRequestState.MERGED);
        PullRequestResponse login = all.getFirst();
        assertThat(login.draft()).isTrue();
        assertThat(login.authorLogin()).isEqualTo("grace");
        assertThat(login.sourceBranch()).isEqualTo("feature-4");
        assertThat(login.targetBranch()).isEqualTo("main");
        assertThat(login.headSha()).isEqualTo(sha(4));
        assertThat(login.updatedAt()).isEqualTo(Instant.parse("2026-10-25T10:00:00Z"));

        assertThat(list(ada, "?state=MERGED").content()).extracting(PullRequestResponse::number).containsExactly(3, 1);
        assertThat(list(ada, "?state=OPEN").totalElements()).isEqualTo(1);

        // Title or author, case-insensitively; a number finds that PR.
        assertThat(list(ada, "?q=npe").content()).extracting(PullRequestResponse::number).containsExactly(3);
        assertThat(list(ada, "?q=GRACE").content()).extracting(PullRequestResponse::number).containsExactly(4);
        assertThat(list(ada, "?q={q}", "#2").content()).extracting(PullRequestResponse::number).containsExactly(2);

        var firstPage = list(ada, "?size=3");
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(list(ada, "?size=3&page=1").content()).extracting(PullRequestResponse::number).containsExactly(1);
    }

    @Test
    void aLaterSyncFetchesOnlyWhatChangedSinceTheLastOne() throws Exception {

        Setup ada = signedInWithOneRepo();
        stubPulls(ada, 1,
                pr(2, "Add search", "open", null, "2026-10-20T10:00:00Z", "ada", false),
                pr(1, "Initial commit", "closed", "2026-10-01T10:00:00Z", "2026-10-01T10:00:00Z", "ada", false));
        sync(ada);

        // #2 got merged just now. #1 is older than the last sync: GitHub lists
        // most recently updated first, so the sync stops there.
        String now = Instant.now().toString();
        stubPulls(ada, 1,
                pr(2, "Add search (v2)", "closed", now, now, "ada", false),
                pr(1, "Initial commit EDITED LONG AGO", "closed", "2026-10-01T10:00:00Z", "2026-10-01T10:00:00Z",
                        "ada", false));

        PullRequestSyncResponse second = sync(ada);

        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.total()).isEqualTo(2);
        List<PullRequestResponse> all = list(ada, "").content();
        assertThat(all.getFirst().title()).isEqualTo("Add search (v2)");
        assertThat(all.getFirst().state()).isEqualTo(PullRequestState.MERGED);
        assertThat(all.get(1).title()).isEqualTo("Initial commit"); // not re-read
        GITHUB.verify(getRequestedFor(urlPathEqualTo(pullsPath(ada)))
                .withQueryParam("state", equalTo("all"))
                .withQueryParam("sort", equalTo("updated"))
                .withQueryParam("direction", equalTo("desc")));
    }

    @Test
    void theFirstSyncReadsEveryPage() throws Exception {

        Setup ada = signedInWithOneRepo();
        String[] fullPage = IntStream.rangeClosed(2, 101).map(i -> 103 - i) // #101 … #2
                .mapToObj(n -> pr(n, "PR " + n, "open", null, "2026-10-01T10:00:00Z", "ada", false))
                .toArray(String[]::new);
        stubPulls(ada, 1, fullPage);
        stubPulls(ada, 2, pr(1, "PR 1", "open", null, "2026-09-01T10:00:00Z", "ada", false));

        assertThat(sync(ada).total()).isEqualTo(101);
        assertThat(list(ada, "?q={q}", "#1").content()).extracting(PullRequestResponse::title).contains("PR 1");
    }

    // ---------- detail + files ----------

    @Test
    void detailShowsTheDescriptionAndFilesAreCachedPerCommit() throws Exception {

        Setup ada = signedInWithOneRepo();
        stubPulls(ada, 1, pr(7, "Add login page", "open", null, "2026-10-25T10:00:00Z", "ada", false));
        sync(ada);
        stubFiles(ada, 7, """
                [{"filename":"src/Login.tsx","status":"added","additions":2,"deletions":0,"changes":2,
                  "patch":"@@ -0,0 +1,2 @@\\n+export function Login() {\\n+}","blob_url":"https://github.com/x"},
                 {"filename":"logo.png","status":"modified","additions":0,"deletions":0,"changes":0},
                 {"filename":"src/new.ts","previous_filename":"src/old.ts","status":"renamed",
                  "additions":0,"deletions":0,"changes":0}]
                """);

        PullRequestDetailResponse detail = rest.exchange(prPath(ada, 7), HttpMethod.GET, bearer(ada.token()),
                PullRequestDetailResponse.class).getBody();
        assertThat(detail.pullRequest().title()).isEqualTo("Add login page");
        assertThat(detail.body()).isEqualTo("Body of #7");

        PullRequestFilesResponse files = files(ada, 7).getBody();
        assertThat(files.headSha()).isEqualTo(sha(7));
        assertThat(files.truncated()).isFalse();
        assertThat(files.files()).extracting(PullRequestFilesResponse.ChangedFile::filename)
                .containsExactly("src/Login.tsx", "logo.png", "src/new.ts");
        assertThat(files.files().getFirst().patch()).isEqualTo("@@ -0,0 +1,2 @@\n+export function Login() {\n+}");
        assertThat(files.files().get(1).patch()).isNull(); // binary: GitHub sends no patch
        assertThat(files.files().get(2).previousFilename()).isEqualTo("src/old.ts");

        // Second time from Redis: GitHub was asked once.
        assertThat(files(ada, 7).getBody()).isEqualTo(files);
        GITHUB.verify(1, getRequestedFor(urlPathEqualTo(pullsPath(ada) + "/7/files")));
    }

    @Test
    void aGitHubFailureIsABadGatewayWithAReadableMessage() throws Exception {

        Setup ada = signedInWithOneRepo();
        stubPulls(ada, 1, pr(7, "Add login page", "open", null, "2026-10-25T10:00:00Z", "ada", false));
        sync(ada);
        GITHUB.stubFor(get(urlPathEqualTo(pullsPath(ada) + "/7/files")).willReturn(aResponse().withStatus(500)));

        ResponseEntity<Map> files = rest.exchange(prPath(ada, 7) + "/files", HttpMethod.GET, bearer(ada.token()),
                Map.class);

        assertThat(files.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat((String) files.getBody().get("message")).startsWith("GitHub didn't respond as expected");

        // GitHub's 404 (e.g. the app lost access to the repository) gets its own explanation.
        GITHUB.stubFor(get(urlPathEqualTo(pullsPath(ada))).willReturn(aResponse().withStatus(404)));
        ResponseEntity<Map> sync = rest.exchange(base(ada) + "/sync", HttpMethod.POST, bearer(ada.token()), Map.class);
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat((String) sync.getBody().get("message")).startsWith("GitHub couldn't find this");
    }

    @Test
    void everythingIsScopedToTheRepositoryOwner() throws Exception {

        Setup ada = signedInWithOneRepo();
        stubPulls(ada, 1, pr(7, "Add login page", "open", null, "2026-10-25T10:00:00Z", "ada", false));
        sync(ada);

        String other = registerAndLogin();
        for (var request : List.of(
                Map.entry(HttpMethod.GET, base(ada)),
                Map.entry(HttpMethod.POST, base(ada) + "/sync"),
                Map.entry(HttpMethod.GET, prPath(ada, 7)),
                Map.entry(HttpMethod.GET, prPath(ada, 7) + "/files"))) {
            assertThat(rest.exchange(request.getValue(), request.getKey(), bearer(other), Map.class).getStatusCode())
                    .as(request.toString()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        // And a number that doesn't exist is a 404 for the owner too.
        assertThat(rest.exchange(prPath(ada, 99), HttpMethod.GET, bearer(ada.token()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------- fake GitHub ----------

    // ---------- helpers ----------

    private static String base(Setup setup) {
        return "/api/github/repositories/" + setup.repositoryId() + "/pull-requests";
    }

    private static String prPath(Setup setup, int number) {
        return base(setup) + "/" + number;
    }

    private PullRequestSyncResponse sync(Setup setup) {
        ResponseEntity<PullRequestSyncResponse> response = rest.exchange(base(setup) + "/sync", HttpMethod.POST,
                bearer(setup.token()), PullRequestSyncResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** {@code query} may contain {placeholders}, filled from {@code values} and encoded once. */
    private Page<PullRequestResponse> list(Setup setup, String query, Object... values) {
        return rest.exchange(base(setup) + query, HttpMethod.GET, bearer(setup.token()),
                new ParameterizedTypeReference<Page<PullRequestResponse>>() {
                }, values).getBody();
    }

    private ResponseEntity<PullRequestFilesResponse> files(Setup setup, int number) {
        return rest.exchange(prPath(setup, number) + "/files", HttpMethod.GET, bearer(setup.token()),
                PullRequestFilesResponse.class);
    }

    private RepositoryResponse repository(Setup setup) {
        return rest.exchange("/api/github/repositories/" + setup.repositoryId(), HttpMethod.GET,
                bearer(setup.token()), RepositoryResponse.class).getBody();
    }
}
