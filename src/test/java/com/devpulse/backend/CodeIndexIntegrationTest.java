package com.devpulse.backend;

import com.devpulse.backend.codeindex.Zips;
import com.devpulse.backend.dto.github.RepositoryResponse;
import com.devpulse.backend.dto.review.ReviewResponse;
import com.devpulse.backend.dto.search.CodeSearchResult;
import com.devpulse.backend.entity.SyncStatus;
import com.devpulse.backend.service.RedisService;
import com.devpulse.backend.service.impl.EmbeddingServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Level 10: a repository's default branch is downloaded (one zipball),
 * chunked, embedded with the local model and stored in pgvector; search
 * finds code by meaning; reviews get related code as context. GitHub is
 * WireMock; the embedding model and pgvector are real.
 */
class CodeIndexIntegrationTest extends GitHubAppIntegrationTest {

    private static final String COMMIT = "c0ffee0000000000000000000000000000000001";

    private static final Map<String, String> SMALL_REPO = Map.of(
            "src/main/java/app/TokenValidator.java", """
                    package app;

                    /** Checks the signature and expiry of incoming JSON web tokens. */
                    public class TokenValidator {
                        public boolean isValid(String jwt) {
                            return verifySignature(jwt) && !isExpired(jwt);
                        }
                    }
                    """,
            "src/main/java/app/InvoicePdf.java", """
                    package app;

                    /** Renders an invoice as a PDF document with a table of line items. */
                    public class InvoicePdf {
                        public byte[] render(Invoice invoice) {
                            return pdf.table(invoice.lines()).toBytes();
                        }
                    }
                    """,
            "web/src/Chart.tsx", """
                    /** A bar chart of monthly revenue. */
                    export function RevenueChart({ months }: { months: number[] }) {
                      return <svg>{months.map((m, i) => <rect key={i} height={m} />)}</svg>
                    }
                    """);

    @Autowired
    private RedisService redisService;

    // ---------- indexing ----------

    @Test
    void indexingDownloadsTheDefaultBranchAndSearchFindsCodeByMeaning() throws Exception {
        Setup ada = signedInWithOneRepo();
        stubSource(ada, SMALL_REPO);

        ResponseEntity<RepositoryResponse> started = rest.exchange(indexPath(ada), HttpMethod.POST,
                bearer(ada.token()), RepositoryResponse.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        RepositoryResponse indexed = awaitIndex(ada);
        assertThat(indexed.indexStatus()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(indexed.indexedCommitSha()).isEqualTo(COMMIT);
        assertThat(indexed.indexedFiles()).isEqualTo(3);
        assertThat(indexed.indexedChunks()).isEqualTo(3);
        assertThat(indexed.indexedAt()).isNotNull();

        // No word of the query appears in TokenValidator's code, but its meaning does.
        List<CodeSearchResult> results = search(ada.token(), "where are JWT tokens checked", null);
        assertThat(results.getFirst().path()).isEqualTo("src/main/java/app/TokenValidator.java");
        assertThat(results.getFirst().startLine()).isEqualTo(1);
        assertThat(results.getFirst().repositoryFullName()).isEqualTo("ada/backend");
        assertThat(results.getFirst().url()).isEqualTo("https://github.com/ada/backend/blob/" + COMMIT
                + "/src/main/java/app/TokenValidator.java#L1-L8");
        assertThat(results.getFirst().score()).isGreaterThan(results.getLast().score());
        assertThat(search(ada.token(), "draw a revenue graph", ada.repositoryId()).getFirst().path())
                .isEqualTo("web/src/Chart.tsx");

        // The downloaded commit is the branch's head.
        GITHUB.verify(getRequestedFor(urlPathEqualTo(repoPath(ada) + "/zipball/" + COMMIT)));
    }

    @Test
    void reIndexingReusesCachedEmbeddingsAndReplacesTheOldChunks() throws Exception {
        Setup ada = signedInWithOneRepo();
        stubSource(ada, SMALL_REPO);
        rest.exchange(indexPath(ada), HttpMethod.POST, bearer(ada.token()), RepositoryResponse.class);
        awaitIndex(ada);
        String cacheKey = "cache:embedding:all-MiniLM-L6-v2:"
                + EmbeddingServiceImpl.sha256(
                "File: web/src/Chart.tsx\n" + SMALL_REPO.get("web/src/Chart.tsx").stripTrailing());
        assertThat(redisService.get(cacheKey)).isNotNull(); // cached by the hash of the embedded text

        // The chart file was deleted on GitHub.
        Map<String, String> without = new LinkedHashMap<>(SMALL_REPO);
        without.remove("web/src/Chart.tsx");
        stubSource(ada, without);
        rest.exchange(indexPath(ada), HttpMethod.POST, bearer(ada.token()), RepositoryResponse.class);
        await().atMost(Duration.ofSeconds(30)).until(() -> repository(ada).indexedFiles() == 2);

        assertThat(repository(ada).indexedChunks()).isEqualTo(2);
        assertThat(search(ada.token(), "draw a revenue graph", null))
                .extracting(CodeSearchResult::path).doesNotContain("web/src/Chart.tsx");
    }

    @Test
    void aDownloadFailureIsReportedOnTheRepository() throws Exception {
        Setup ada = signedInWithOneRepo();
        GITHUB.stubFor(get(urlPathEqualTo(repoPath(ada) + "/branches/main")).willReturn(aResponse().withStatus(500)));

        rest.exchange(indexPath(ada), HttpMethod.POST, bearer(ada.token()), RepositoryResponse.class);
        RepositoryResponse failed = awaitIndex(ada);

        assertThat(failed.indexStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(failed.indexError()).isEqualTo("Couldn't download the repository from GitHub. Please try again.");
    }

    // ---------- search rules ----------

    @Test
    void searchOnlySeesYourOwnIndexedCode() throws Exception {
        Setup ada = signedInWithOneRepo();
        stubSource(ada, SMALL_REPO);
        rest.exchange(indexPath(ada), HttpMethod.POST, bearer(ada.token()), RepositoryResponse.class);
        awaitIndex(ada);

        String other = registerAndLogin();
        assertThat(search(other, "JWT tokens", null)).isEmpty();
        assertThat(rest.exchange("/api/search?q=jwt&repositoryId=" + ada.repositoryId(), HttpMethod.GET,
                bearer(other), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange(indexPath(ada), HttpMethod.POST, bearer(other), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> blank = rest.exchange("/api/search?q= ", HttpMethod.GET, bearer(ada.token()), Map.class);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> tooLong = rest.exchange("/api/search?q={q}", HttpMethod.GET, bearer(ada.token()),
                Map.class, "x".repeat(501));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- RAG ----------

    @Test
    void reviewsOfAnIndexedRepositoryGetRelatedCodeAsContext() throws Exception {
        Setup ada = signedInWithOneRepo();
        stubSource(ada, SMALL_REPO);
        rest.exchange(indexPath(ada), HttpMethod.POST, bearer(ada.token()), RepositoryResponse.class);
        awaitIndex(ada);
        stubPulls(ada, 1, pr(5, "Use the token validator", "open", null, "2026-10-25T10:00:00Z", "ada", false));
        rest.exchange("/api/github/repositories/" + ada.repositoryId() + "/pull-requests/sync", HttpMethod.POST,
                bearer(ada.token()), Map.class);
        stubFiles(ada, 5, """
                [{"filename":"src/main/java/app/AuthFilter.java","status":"added","additions":1,"deletions":0,
                  "changes":1,"patch":"@@ -0,0 +1 @@\\n+if (!validator.isValid(jwt)) { reject(); }"}]
                """);

        Long reviewId = rest.exchange("/api/github/repositories/" + ada.repositoryId() + "/pull-requests/5/reviews",
                HttpMethod.POST, bearer(ada.token()), ReviewResponse.class).getBody().id();
        ReviewResponse review = await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> (ReviewResponse) rest.exchange("/api/github/repositories/" + ada.repositoryId()
                                        + "/pull-requests/5/reviews", HttpMethod.GET, bearer(ada.token()),
                                new ParameterizedTypeReference<List<ReviewResponse>>() {
                                }).getBody().stream().filter(r -> r.id().equals(reviewId)).findFirst().orElseThrow(),
                        r -> r.finishedAt() != null);

        assertThat(review.contextChunks()).isEqualTo(3); // the three indexed chunks, none from the changed file
    }

    // ---------- the plan's quality check, on DevPulse's own code ----------

    @Test
    void searchingDevPulsesOwnCodeFindsWhereJwtTokensAreValidated() throws Exception {
        Setup ada = signedInWithOneRepo();
        stubSource(ada, sourceTree(Path.of("src/main/java")));

        rest.exchange(indexPath(ada), HttpMethod.POST, bearer(ada.token()), RepositoryResponse.class);
        RepositoryResponse indexed = await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofMillis(500))
                .until(() -> repository(ada), r -> r.indexStatus() == SyncStatus.SUCCEEDED
                        || r.indexStatus() == SyncStatus.FAILED);
        assertThat(indexed.indexStatus()).isEqualTo(SyncStatus.SUCCEEDED);
        assertThat(indexed.indexedFiles()).isGreaterThan(100);

        List<String> top3 = search(ada.token(), "where are JWT tokens validated", null).stream()
                .limit(3).map(CodeSearchResult::path).toList();

        assertThat(top3).anyMatch(path -> path.endsWith("security/JwtAuthenticationFilter.java")
                || path.endsWith("security/JwtService.java"));
    }

    // ---------- helpers ----------

    private static String repoPath(Setup setup) {
        return "/repositories/" + setup.identity().repoId(1);
    }

    private static String indexPath(Setup setup) {
        return "/api/github/repositories/" + setup.repositoryId() + "/index";
    }

    /** GitHub: "main" points at COMMIT, and COMMIT's zipball holds these files. */
    private void stubSource(Setup setup, Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((path, text) -> bytes.put(path, text.getBytes(StandardCharsets.UTF_8)));
        String auth = "Bearer " + setup.identity().installationToken();
        GITHUB.stubFor(get(urlPathEqualTo(repoPath(setup) + "/branches/main")).withHeader("Authorization", equalTo(auth))
                .willReturn(okJson("{\"name\":\"main\",\"commit\":{\"sha\":\"" + COMMIT + "\"}}")));
        GITHUB.stubFor(get(urlPathEqualTo(repoPath(setup) + "/zipball/" + COMMIT))
                .withHeader("Authorization", equalTo(auth))
                .willReturn(aResponse().withHeader("Content-Type", "application/zip").withBody(Zips.zipball(bytes))));
    }

    /** Every file under a folder of this checkout, by its path in the repository. */
    private static Map<String, String> sourceTree(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            Map<String, String> files = new LinkedHashMap<>();
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(path.toString().replace('\\', '/'), Files.readString(path));
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RepositoryResponse repository(Setup setup) {
        return rest.exchange("/api/github/repositories/" + setup.repositoryId(), HttpMethod.GET, bearer(setup.token()),
                RepositoryResponse.class).getBody();
    }

    private RepositoryResponse awaitIndex(Setup setup) {
        return await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> repository(setup), r -> r.indexStatus() == SyncStatus.SUCCEEDED
                        || r.indexStatus() == SyncStatus.FAILED);
    }

    private List<CodeSearchResult> search(String token, String query, Long repositoryId) {
        return rest.exchange("/api/search?q={q}" + (repositoryId == null ? "" : "&repositoryId=" + repositoryId),
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(token)),
                new ParameterizedTypeReference<List<CodeSearchResult>>() {
                }, query).getBody();
    }
}
