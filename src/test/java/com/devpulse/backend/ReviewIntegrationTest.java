package com.devpulse.backend;

import com.devpulse.backend.dto.review.AIUsageResponse;
import com.devpulse.backend.dto.review.ReviewDetailResponse;
import com.devpulse.backend.dto.review.ReviewFindingResponse;
import com.devpulse.backend.dto.review.ReviewResponse;
import com.devpulse.backend.entity.FindingSource;
import com.devpulse.backend.entity.PullRequest;
import com.devpulse.backend.entity.Review;
import com.devpulse.backend.entity.ReviewStatus;
import com.devpulse.backend.entity.Severity;
import com.devpulse.backend.repository.PullRequestRepository;
import com.devpulse.backend.repository.ReviewRepository;
import com.devpulse.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Level 7: an AI review of a pull request runs in the background with the
 * mock provider plus DevPulse's security rules, and stores findings on the
 * right lines. GitHub is WireMock.
 */
class ReviewIntegrationTest extends GitHubAppIntegrationTest {

    // Line 2 builds SQL from a string (a rule), line 3 prints to the console (the mock AI).
    private static final String FILES = """
            [{"filename":"src/OrderDao.java","status":"modified","additions":2,"deletions":1,"changes":3,
              "patch":"@@ -10,2 +10,3 @@\\n public List<Order> find(String email) {\\n-  return jdbc.query(Q, email);\\n+  return jdbc.query(\\"SELECT * FROM orders WHERE email = '\\" + email + \\"'\\", m);\\n+  System.out.println(email);"},
             {"filename":"package-lock.json","status":"modified","additions":1,"deletions":1,"changes":2,
              "patch":"@@ -1 +1 @@\\n-a\\n+b"}]
            """;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void aReviewRunsInTheBackgroundAndPutsFindingsOnTheRightLines() throws Exception {

        Setup ada = withPullRequest(7);

        ResponseEntity<ReviewResponse> started = rest.exchange(reviewsPath(ada, 7), HttpMethod.POST,
                bearer(ada.token()), ReviewResponse.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(started.getBody().provider()).isEqualTo("mock");
        assertThat(started.getBody().headSha()).isEqualTo(sha(7));

        ReviewDetailResponse detail = awaitReview(ada, started.getBody().id());
        ReviewResponse review = detail.review();

        assertThat(review.status()).isEqualTo(ReviewStatus.SUCCEEDED);
        assertThat(review.filesReviewed()).isEqualTo(1);
        assertThat(review.filesSkipped()).isEqualTo(1); // package-lock.json
        assertThat(review.chunks()).isEqualTo(1);
        assertThat(review.promptTokens()).isPositive();
        assertThat(review.estimatedCostUsd()).isEqualByComparingTo("0"); // the mock is free
        assertThat(review.pullRequestNumber()).isEqualTo(7);
        assertThat(review.repositoryFullName()).isEqualTo("ada/backend");

        assertThat(detail.findings())
                .extracting(ReviewFindingResponse::source, ReviewFindingResponse::severity,
                        ReviewFindingResponse::title, ReviewFindingResponse::filePath, ReviewFindingResponse::line)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(FindingSource.RULE, Severity.HIGH,
                                "SQL built from strings", "src/OrderDao.java", 11),
                        org.assertj.core.groups.Tuple.tuple(FindingSource.AI, Severity.LOW,
                                "Debug output left in", "src/OrderDao.java", 12));
        assertThat(review.openFindings().high()).isEqualTo(1);
        assertThat(review.openFindings().low()).isEqualTo(1);

        // History of the pull request, the user's list, and usage all see it.
        List<ReviewResponse> history = rest.exchange(reviewsPath(ada, 7), HttpMethod.GET, bearer(ada.token()),
                new ParameterizedTypeReference<List<ReviewResponse>>() {
                }).getBody();
        assertThat(history).extracting(ReviewResponse::id).containsExactly(review.id());

        Page<ReviewResponse> mine = rest.exchange("/api/reviews", HttpMethod.GET, bearer(ada.token()),
                new ParameterizedTypeReference<Page<ReviewResponse>>() {
                }).getBody();
        assertThat(mine.content()).extracting(ReviewResponse::id).containsExactly(review.id());

        AIUsageResponse usage = rest.exchange("/api/reviews/usage", HttpMethod.GET, bearer(ada.token()),
                AIUsageResponse.class).getBody();
        assertThat(usage.reviewsToday()).isEqualTo(1);
        assertThat(usage.dailyReviewLimit()).isEqualTo(20);
        assertThat(usage.tokensThisMonth()).isEqualTo(review.promptTokens() + review.completionTokens());
        assertThat(usage.costTodayUsd()).isEqualByComparingTo("0");
    }

    @Test
    void dismissingAFindingHidesItFromTheCounts() throws Exception {

        Setup ada = withPullRequest(7);
        ReviewDetailResponse detail = awaitReview(ada, start(ada, 7).id());
        ReviewFindingResponse rule = detail.findings().getFirst();

        ReviewDetailResponse dismissed = dismiss(ada, detail.review().id(), rule.id(), true).getBody();
        assertThat(dismissed.review().openFindings().high()).isZero();
        assertThat(dismissed.findings().getFirst().dismissed()).isTrue();

        ReviewDetailResponse restored = dismiss(ada, detail.review().id(), rule.id(), false).getBody();
        assertThat(restored.review().openFindings().high()).isEqualTo(1);

        // A finding of another review, and a missing body, are rejected.
        assertThat(dismiss(ada, detail.review().id(), 999_999L, true).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange("/api/reviews/" + detail.review().id() + "/findings/" + rule.id(),
                HttpMethod.PATCH, new HttpEntity<>(Map.of(), bearerHeaders(ada.token())), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reviewsBelongToTheirUser() throws Exception {

        Setup ada = withPullRequest(7);
        Long reviewId = start(ada, 7).id();
        awaitReview(ada, reviewId);

        String other = registerAndLogin();
        assertThat(rest.exchange("/api/reviews/" + reviewId, HttpMethod.GET, bearer(other), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange(reviewsPath(ada, 7), HttpMethod.POST, bearer(other), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange(reviewsPath(ada, 7), HttpMethod.GET, bearer(other), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Page<?> theirs = rest.exchange("/api/reviews", HttpMethod.GET, bearer(other),
                new ParameterizedTypeReference<Page<ReviewResponse>>() {
                }).getBody();
        assertThat(theirs.totalElements()).isZero();
    }

    @Test
    void aGitHubFailureFailsTheReviewWithAReason() throws Exception {

        Setup ada = withPullRequest(7);
        GITHUB.stubFor(get(urlPathEqualTo(pullsPath(ada) + "/7/files")).willReturn(aResponse().withStatus(500)));

        ReviewDetailResponse detail = awaitReview(ada, start(ada, 7).id());

        assertThat(detail.review().status()).isEqualTo(ReviewStatus.FAILED);
        assertThat(detail.review().error()).startsWith("Couldn't load the pull request's changes from GitHub");
        assertThat(detail.findings()).isEmpty();
    }

    @Test
    void theDatabaseAllowsOneActiveReviewPerPullRequest() throws Exception {

        Setup ada = withPullRequest(7);
        PullRequest pullRequest = pullRequestRepository
                .findOwned(userIdOf(ada), ada.repositoryId(), 7).orElseThrow();

        reviewRepository.saveAndFlush(queued(pullRequest, ada));
        assertThatThrownBy(() -> reviewRepository.saveAndFlush(queued(pullRequest, ada)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // ...so the API answers 409 while one is queued.
        ResponseEntity<Map> second = rest.exchange(reviewsPath(ada, 7), HttpMethod.POST, bearer(ada.token()),
                Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) second.getBody().get("message")).isEqualTo("A review of this pull request is already running.");
    }

    // ---------- helpers ----------

    /** Ada, signed in with one repository whose pull request #number is synced and has FILES. */
    private Setup withPullRequest(int number) throws Exception {
        Setup setup = signedInWithOneRepo();
        stubPulls(setup, 1, pr(number, "Add order lookup", "open", null, "2026-10-25T10:00:00Z", "ada", false));
        rest.exchange("/api/github/repositories/" + setup.repositoryId() + "/pull-requests/sync", HttpMethod.POST,
                bearer(setup.token()), Map.class);
        stubFiles(setup, number, FILES);
        return setup;
    }

    private static String reviewsPath(Setup setup, int number) {
        return "/api/github/repositories/" + setup.repositoryId() + "/pull-requests/" + number + "/reviews";
    }

    private ReviewResponse start(Setup setup, int number) {
        return rest.exchange(reviewsPath(setup, number), HttpMethod.POST, bearer(setup.token()), ReviewResponse.class)
                .getBody();
    }

    private ReviewDetailResponse awaitReview(Setup setup, Long reviewId) {
        return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> rest.exchange("/api/reviews/" + reviewId, HttpMethod.GET, bearer(setup.token()),
                                ReviewDetailResponse.class).getBody(),
                        detail -> detail.review().status() == ReviewStatus.SUCCEEDED
                                || detail.review().status() == ReviewStatus.FAILED);
    }

    private ResponseEntity<ReviewDetailResponse> dismiss(Setup setup, Long reviewId, Long findingId, boolean value) {
        return rest.exchange("/api/reviews/" + reviewId + "/findings/" + findingId, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("dismissed", value), bearerHeaders(setup.token())), ReviewDetailResponse.class);
    }

    private Long userIdOf(Setup setup) {
        return userRepository.findByEmailIgnoreCase("user-" + setup.identity().githubUserId() + "@example.com")
                .orElseThrow().getId();
    }

    private Review queued(PullRequest pullRequest, Setup setup) {
        Review review = new Review();
        review.setPullRequest(pullRequest);
        review.setUser(userRepository.getReferenceById(userIdOf(setup)));
        review.setHeadSha(pullRequest.getHeadSha());
        review.setProvider("mock");
        review.setModel("mock-reviewer");
        return review;
    }
}
