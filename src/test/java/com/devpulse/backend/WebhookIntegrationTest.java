package com.devpulse.backend;

import com.devpulse.backend.dto.github.PullRequestDetailResponse;
import com.devpulse.backend.dto.review.ReviewResponse;
import com.devpulse.backend.dto.settings.ReviewSettingsResponse;
import com.devpulse.backend.dto.webhook.WebhookDeliveryResponse;
import com.devpulse.backend.entity.ReviewStatus;
import com.devpulse.backend.entity.ReviewTrigger;
import com.devpulse.backend.entity.Severity;
import com.devpulse.backend.entity.WebhookDeliveryStatus;
import com.devpulse.backend.security.WebhookSignature;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Level 9: GitHub webhooks (signed, deduplicated, handled in the background)
 * start reviews automatically, and findings are published to GitHub as a
 * pull request review. GitHub is WireMock; the secret is in application-test.properties.
 */
class WebhookIntegrationTest extends GitHubAppIntegrationTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String NEW_SHA = "1234567890abcdef1234567890abcdef12345678";

    // Line 1 builds SQL from a string (a HIGH rule), line 2 prints to the console (a LOW AI finding).
    private static final String FILES = """
            [{"filename":"src/Dao.java","status":"added","additions":2,"deletions":0,"changes":2,
              "patch":"@@ -0,0 +1,2 @@\\n+q = \\"SELECT * FROM t WHERE id = '\\" + id + \\"'\\";\\n+System.out.println(q);"}]
            """;

    // ---------- receiving ----------

    @Test
    void onlySignedDeliveriesAreAcceptedAndEachOnlyOnce() throws Exception {
        String body = "{\"zen\":\"Keep it logically awesome.\",\"hook_id\":1}";
        String deliveryId = UUID.randomUUID().toString();

        assertThat(deliver("ping", deliveryId, body, "sha256=" + "0".repeat(64)).statusCode()).isEqualTo(401);
        assertThat(deliver("ping", deliveryId, body, null).statusCode()).isEqualTo(401);

        HttpResponse<String> ping = deliver("ping", deliveryId, body, WebhookSignature.sign(SECRET, bytes(body)));
        assertThat(ping.statusCode()).isEqualTo(200);
        assertThat(ping.body()).isEqualTo("pong");

        // GitHub retries, or someone presses "Redeliver": the same delivery id is ignored.
        HttpResponse<String> again = deliver("ping", deliveryId, body, WebhookSignature.sign(SECRET, bytes(body)));
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.body()).isEqualTo("duplicate delivery, ignored");

        HttpResponse<String> notJson = deliver("ping", UUID.randomUUID().toString(), "nope",
                WebhookSignature.sign(SECRET, bytes("nope")));
        assertThat(notJson.statusCode()).isEqualTo(400);
    }

    // ---------- automatic reviews ----------

    @Test
    void aPushToAPullRequestUpdatesItAndStartsAReview() throws Exception {
        Setup ada = withPullRequest(7, true);

        HttpResponse<String> response = deliverPullRequest(ada, "synchronize", 7, false);
        assertThat(response.statusCode()).isEqualTo(202);

        ReviewResponse review = awaitLatestReview(ada, 7);
        assertThat(review.status()).isEqualTo(ReviewStatus.SUCCEEDED);
        assertThat(review.triggeredBy()).isEqualTo(ReviewTrigger.WEBHOOK);
        assertThat(review.headSha()).isEqualTo(NEW_SHA); // the PR row was updated from the event first
        assertThat(review.publishedAt()).isNull(); // automatic publishing is off by default

        PullRequestDetailResponse pr = rest.exchange(prPath(ada, 7), HttpMethod.GET, bearer(ada.token()),
                PullRequestDetailResponse.class).getBody();
        assertThat(pr.pullRequest().title()).isEqualTo("Add DAO (v2)");

        WebhookDeliveryResponse delivery = awaitDelivery(ada);
        assertThat(delivery.event()).isEqualTo("pull_request");
        assertThat(delivery.action()).isEqualTo("synchronize");
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
        assertThat(delivery.detail()).isEqualTo("#7 synchronize. Review started.");
    }

    @Test
    void noReviewForDraftsReposWithReviewOffOrUsersWhoTurnedItOff() throws Exception {
        Setup off = withPullRequest(7, false);
        deliverPullRequest(off, "opened", 7, false);
        assertThat(awaitDelivery(off).detail()).isEqualTo("#7 opened. AI review is off for this repository.");

        Setup draft = withPullRequest(7, true);
        deliverPullRequest(draft, "synchronize", 7, true);
        assertThat(awaitDelivery(draft).detail()).isEqualTo("#7 synchronize. Draft: no review.");

        Setup optedOut = withPullRequest(7, true);
        saveSettings(optedOut, false, false, Severity.MEDIUM);
        deliverPullRequest(optedOut, "synchronize", 7, false);
        assertThat(awaitDelivery(optedOut).detail())
                .isEqualTo("#7 synchronize. Automatic reviews are off in this user's settings.");

        for (Setup setup : List.of(off, draft, optedOut)) {
            assertThat(reviews(setup, 7)).isEmpty();
        }
    }

    @Test
    void eventsForRepositoriesDevPulseDoesntFollowAreIgnored() throws Exception {
        Setup ada = withPullRequest(7, true);
        String body = """
                {"action":"opened","number":1,"pull_request":%s,
                 "repository":{"id":999999},"installation":{"id":%d}}
                """.formatted(pr(1, "Elsewhere", "open", null, "2026-10-25T10:00:00Z", "ada", false),
                ada.identity().installationId());

        deliver("pull_request", UUID.randomUUID().toString(), body, WebhookSignature.sign(SECRET, bytes(body)));

        WebhookDeliveryResponse delivery = awaitDelivery(ada);
        assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.IGNORED);
        assertThat(delivery.detail()).isEqualTo("#1 is in a repository DevPulse doesn't follow.");
    }

    // ---------- publishing ----------

    @Test
    void publishingPostsOneGitHubReviewWithInlineComments() throws Exception {
        Setup ada = withPullRequest(7, true);
        stubCreateReview(ada, 7, okJson("""
                {"id":555,"html_url":"https://github.com/ada/backend/pull/7#pullrequestreview-555"}
                """));
        Long reviewId = rest.exchange(reviewsPath(ada, 7), HttpMethod.POST, bearer(ada.token()),
                ReviewResponse.class).getBody().id();
        awaitReviewDone(ada, reviewId);

        ResponseEntity<ReviewResponse> published = rest.exchange("/api/reviews/" + reviewId + "/publish",
                HttpMethod.POST, bearer(ada.token()), ReviewResponse.class);

        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody().publishedAt()).isNotNull();
        assertThat(published.getBody().githubReviewUrl()).endsWith("#pullrequestreview-555");
        assertThat(published.getBody().publishedComments()).isEqualTo(1); // HIGH only: LOW is below MEDIUM

        LoggedRequest sent = GITHUB.findAll(postRequestedFor(urlPathEqualTo(reviewsOnGitHub(ada, 7)))).getFirst();
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + ada.identity().installationToken());
        assertThat(sent.getBodyAsString())
                .contains("\"commit_id\":\"" + sha(7) + "\"")
                .contains("\"event\":\"COMMENT\"")
                .contains("\"path\":\"src/Dao.java\"", "\"line\":1", "\"side\":\"RIGHT\"")
                .contains("SQL built from strings")
                .doesNotContain("Debug output left in");

        // Once is enough.
        ResponseEntity<Map> twice = rest.exchange("/api/reviews/" + reviewId + "/publish", HttpMethod.POST,
                bearer(ada.token()), Map.class);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) twice.getBody().get("message")).isEqualTo("This review is already on GitHub.");

        // Someone else's review is a 404.
        assertThat(rest.exchange("/api/reviews/" + reviewId + "/publish", HttpMethod.POST,
                bearer(registerAndLogin()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void automaticReviewsArePublishedWhenTheUserAsksForIt() throws Exception {
        Setup ada = withPullRequest(7, true);
        saveSettings(ada, true, true, Severity.LOW);
        stubCreateReview(ada, 7, okJson("{\"id\":777,\"html_url\":\"https://github.com/r/777\"}"));

        deliverPullRequest(ada, "synchronize", 7, false);

        ReviewResponse review = await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> latestReview(ada, 7), r -> r != null && r.publishedAt() != null);
        assertThat(review.publishedComments()).isEqualTo(2); // LOW included this time
        assertThat(review.githubReviewUrl()).isEqualTo("https://github.com/r/777");
    }

    @Test
    void whenGitHubRefusesTheReviewTheUserIsToldWhy() throws Exception {
        Setup ada = withPullRequest(7, true);
        stubCreateReview(ada, 7, aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                .withBody("{\"message\":\"Unprocessable Entity\",\"errors\":[\"Line could not be resolved\"]}"));
        Long reviewId = rest.exchange(reviewsPath(ada, 7), HttpMethod.POST, bearer(ada.token()),
                ReviewResponse.class).getBody().id();
        awaitReviewDone(ada, reviewId);

        ResponseEntity<Map> refused = rest.exchange("/api/reviews/" + reviewId + "/publish", HttpMethod.POST,
                bearer(ada.token()), Map.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat((String) refused.getBody().get("message")).startsWith("GitHub refused the request.");
        assertThat(latestReview(ada, 7).publishedAt()).isNull(); // nothing recorded: it can be tried again
    }

    // ---------- installation events ----------

    @Test
    void uninstallingTheAppRemovesItsRepositories() throws Exception {
        Setup ada = withPullRequest(7, true);
        String body = """
                {"action":"deleted","installation":{"id":%d}}
                """.formatted(ada.identity().installationId());

        deliver("installation", UUID.randomUUID().toString(), body, WebhookSignature.sign(SECRET, bytes(body)));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(rest.exchange(
                "/api/github/repositories", HttpMethod.GET, bearer(ada.token()),
                new ParameterizedTypeReference<Page<Object>>() {
                }).getBody().totalElements()).isZero());
    }

    @Test
    void addingRepositoriesToTheInstallationSyncsThem() throws Exception {
        Setup ada = withPullRequest(7, true);
        // On GitHub, ada/frontend was added to the installation.
        stubInstallationRepositories(ada.identity(),
                repo(ada.identity().repoId(1), "ada/backend", null, false, "Java", null),
                repo(ada.identity().repoId(2), "ada/frontend", null, false, "TypeScript", null));
        String body = """
                {"action":"added","installation":{"id":%d},"repositories_added":[{"id":%d}]}
                """.formatted(ada.identity().installationId(), ada.identity().repoId(2));

        deliver("installation_repositories", UUID.randomUUID().toString(), body,
                WebhookSignature.sign(SECRET, bytes(body)));

        assertThat(awaitDelivery(ada).detail()).isEqualTo("Repositories changed on GitHub: syncing.");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(rest.exchange(
                "/api/github/repositories", HttpMethod.GET, bearer(ada.token()),
                new ParameterizedTypeReference<Page<Object>>() {
                }).getBody().totalElements()).isEqualTo(2));
    }

    // ---------- settings ----------

    @Test
    void settingsHaveDefaultsAndCanBeChanged() throws Exception {
        String token = registerAndLogin();

        ReviewSettingsResponse defaults = rest.exchange("/api/settings/reviews", HttpMethod.GET, bearer(token),
                ReviewSettingsResponse.class).getBody();
        assertThat(defaults).isEqualTo(new ReviewSettingsResponse(true, false, Severity.MEDIUM, true));

        ReviewSettingsResponse saved = rest.exchange("/api/settings/reviews", HttpMethod.PUT,
                new HttpEntity<>(Map.of("autoReview", false, "autoPublish", true, "minPublishSeverity", "HIGH"),
                        bearerHeaders(token)), ReviewSettingsResponse.class).getBody();
        assertThat(saved).isEqualTo(new ReviewSettingsResponse(false, true, Severity.HIGH, true));

        assertThat(rest.exchange("/api/settings/reviews", HttpMethod.PUT,
                new HttpEntity<>(Map.of("autoReview", true), bearerHeaders(token)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- helpers ----------

    /** Ada with pull request #7 synced, its files stubbed, AI review on or off for the repository. */
    private Setup withPullRequest(int number, boolean reviewEnabled) throws Exception {
        Setup setup = signedInWithOneRepo();
        stubPulls(setup, 1, pr(number, "Add DAO", "open", null, "2026-10-25T10:00:00Z", "ada", false));
        rest.exchange("/api/github/repositories/" + setup.repositoryId() + "/pull-requests/sync", HttpMethod.POST,
                bearer(setup.token()), Map.class);
        stubFiles(setup, number, FILES);
        rest.exchange("/api/github/repositories/" + setup.repositoryId(), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("reviewEnabled", reviewEnabled), bearerHeaders(setup.token())), Map.class);
        return setup;
    }

    /** A pull_request event for #number: new title, new head commit (NEW_SHA). */
    private HttpResponse<String> deliverPullRequest(Setup setup, String action, int number, boolean draft)
            throws IOException, InterruptedException {
        String pr = pr(number, "Add DAO (v2)", "open", null, "2026-10-26T10:00:00Z", "ada", draft)
                .replace(sha(number), NEW_SHA);
        String body = """
                {"action":"%s","number":%d,"pull_request":%s,"repository":{"id":%d},"installation":{"id":%d}}
                """.formatted(action, number, pr, setup.identity().repoId(1), setup.identity().installationId());
        return deliver("pull_request", UUID.randomUUID().toString(), body, WebhookSignature.sign(SECRET, bytes(body)));
    }

    private HttpResponse<String> deliver(String event, String deliveryId, String body, String signature)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/github/webhooks"))
                .header("Content-Type", "application/json")
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", deliveryId)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private void stubCreateReview(Setup setup, int number, ResponseDefinitionBuilder response) {
        GITHUB.stubFor(post(urlPathEqualTo(reviewsOnGitHub(setup, number)))
                .withHeader("Authorization", equalTo("Bearer " + setup.identity().installationToken()))
                .willReturn(response));
    }

    private static String reviewsOnGitHub(Setup setup, int number) {
        return pullsPath(setup) + "/" + number + "/reviews";
    }

    private static String prPath(Setup setup, int number) {
        return "/api/github/repositories/" + setup.repositoryId() + "/pull-requests/" + number;
    }

    private static String reviewsPath(Setup setup, int number) {
        return prPath(setup, number) + "/reviews";
    }

    private List<ReviewResponse> reviews(Setup setup, int number) {
        return rest.exchange(reviewsPath(setup, number), HttpMethod.GET, bearer(setup.token()),
                new ParameterizedTypeReference<List<ReviewResponse>>() {
                }).getBody();
    }

    private ReviewResponse latestReview(Setup setup, int number) {
        List<ReviewResponse> reviews = reviews(setup, number);
        return reviews.isEmpty() ? null : reviews.getFirst();
    }

    private ReviewResponse awaitLatestReview(Setup setup, int number) {
        return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> latestReview(setup, number), r -> r != null && r.status() != ReviewStatus.QUEUED
                        && r.status() != ReviewStatus.RUNNING);
    }

    private void awaitReviewDone(Setup setup, Long reviewId) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> rest.exchange("/api/reviews/" + reviewId, HttpMethod.GET, bearer(setup.token()),
                                Map.class).getBody(),
                        detail -> "SUCCEEDED".equals(((Map<?, ?>) detail.get("review")).get("status")));
    }

    /** The newest delivery for the setup's installation, once its background handling finished. */
    private WebhookDeliveryResponse awaitDelivery(Setup setup) {
        return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> rest.exchange("/api/github/webhooks/deliveries", HttpMethod.GET, bearer(setup.token()),
                                new ParameterizedTypeReference<List<WebhookDeliveryResponse>>() {
                                }).getBody(),
                        list -> !list.isEmpty() && list.getFirst().status() != WebhookDeliveryStatus.RECEIVED)
                .getFirst();
    }

    private void saveSettings(Setup setup, boolean autoReview, boolean autoPublish, Severity minimum) {
        rest.exchange("/api/settings/reviews", HttpMethod.PUT, new HttpEntity<>(Map.of("autoReview", autoReview,
                "autoPublish", autoPublish, "minPublishSeverity", minimum.name()), bearerHeaders(setup.token())),
                ReviewSettingsResponse.class);
    }
}
